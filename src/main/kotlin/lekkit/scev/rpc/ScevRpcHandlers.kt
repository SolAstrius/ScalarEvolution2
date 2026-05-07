/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcErrors
import lekkit.scev.core.rpc.RpcHandler
import lekkit.scev.core.rpc.RpcProtocol

import com.mojang.logging.LogUtils
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.server.MachineManager
import net.minecraft.SharedConstants
import net.neoforged.fml.ModList
import java.util.UUID

/**
 * Factory for the built-in RPC handlers every machine gets regardless
 * of CC attachment. Keeps CC-dependent handlers out of this file so
 * adding a CC-Tweaked dep later doesn't pull a big surface in.
 *
 * Current built-ins:
 *  - `ping` — returns `"pong"`. Liveness + handshake.
 *  - `log level msg` — server-side log at the given slf4j level.
 *
 * CC-surface methods (`list`, `call`, …) are installed by
 * `ScevCCHandlers.install` whenever CC: Tweaked is present. The stubs
 * below are the fallback for servers without CC installed — they
 * return a clean "not installed" error instead of "unknown method".
 */
object ScevRpcHandlers {
    private val LOG = LogUtils.getLogger()

    /**
     * Protocol-version advertised via [RpcProtocol.METHOD_SELF].
     * Bumped whenever the wire format changes in a way older guests
     * would mis-decode. v1 covers structured `{code, message}` errors
     * + TAG_CHUNKED chunked-transfer; pre-v1 hosts spoke bare-string
     * errors and capped at 8 KiB frames.
     */
    const val PROTOCOL_VERSION: Long = 1

    /**
     * Capability flags returned in `self.capabilities`. Each entry is
     * a flag the guest can branch on without inferring it from
     * [PROTOCOL_VERSION] — minor additions inside a major version
     * stay forward-compatible because guests query by name.
     */
    private fun capabilitiesMap(): Map<MsgValue, MsgValue> = linkedMapOf(
        MsgValue.of("structured_errors") to MsgValue.of(true),
        MsgValue.of("chunked_transfer") to MsgValue.of(true),
        MsgValue.of("frame_too_large_signal") to MsgValue.of(true),
        MsgValue.of("cooked_mode_recovery") to MsgValue.of(true),
        MsgValue.of("batch") to MsgValue.of(true),
        MsgValue.of("batch_par") to MsgValue.of(true),
        // Server-side event filtering via subscribe/unsubscribe.
        // Pre-this-bump hosts had the methods registered but they
        // were no-ops; clients can branch on this flag to avoid
        // doing redundant client-side filtering.
        MsgValue.of("event_subscriptions") to MsgValue.of(true),
    )

    /** Wire-level numbers the guest needs to budget its own traffic. */
    private fun limitsMap(): Map<MsgValue, MsgValue> = linkedMapOf(
        // Max plaintext (pre-COBS) MessagePack payload either side
        // will accept; bigger Responses get chunked, bigger Events /
        // Requests are dropped at the wire boundary.
        MsgValue.of("frame_max_bytes") to MsgValue.of(ScevRpcManager.MAX_FRAME_BYTES.toLong()),
        // Per-stream and aggregate caps on the chunk store. Once
        // breached the host emits FRAME_TOO_LARGE in place of the
        // chunked marker so the guest knows pagination is needed.
        MsgValue.of("chunk_max_streams") to MsgValue.of(ChunkStore.DEFAULT_MAX_STREAMS.toLong()),
        MsgValue.of("chunk_max_stream_bytes") to MsgValue.of(ChunkStore.DEFAULT_MAX_STREAM_BYTES.toLong()),
        MsgValue.of("chunk_max_total_bytes") to MsgValue.of(ChunkStore.DEFAULT_MAX_TOTAL_BYTES),
        MsgValue.of("chunk_ttl_ms") to MsgValue.of(ChunkStore.DEFAULT_TTL_MS),
    )

    @JvmStatic
    fun registerDefaults(d: RpcDispatcher, machineUuid: UUID, chunkStore: ChunkStore) {
        d.register(RpcProtocol.METHOD_PING) { _ -> MsgValue.of("pong") }

        // Chunked-transfer pull surface: read_chunk(streamId, offset, max)
        // returns a binary slice of a cached oversized response;
        // discard_chunk(streamId) drops it early. Both surface a
        // structured no_such_peer-style error when the stream is
        // unknown — eviction or expiry are the most common causes,
        // and the guest can react by re-issuing the original request.
        d.register(RpcProtocol.METHOD_READ_CHUNK) { args ->
            readChunk(chunkStore, args)
        }
        d.register(RpcProtocol.METHOD_DISCARD_CHUNK) { args ->
            discardChunk(chunkStore, args)
        }

        // Ordered batch dispatch — runs items sequentially, results
        // come back in input order with per-item err/result pairs.
        // Re-uses the same dispatcher to dispatch each item, so any
        // method already registered is automatically batchable
        // without per-handler opt-in.
        d.register(RpcProtocol.METHOD_BATCH) { args ->
            batch(d, args)
        }
        // Parallel batch — same envelope, but items run concurrently.
        // Per-peripheral mutex on the host serialises same-peer calls;
        // cross-peer calls actually fan out. Always runs every item.
        d.register(RpcProtocol.METHOD_BATCH_PAR) { args ->
            batchPar(d, args)
        }

        d.register(RpcProtocol.METHOD_LOG) { args ->
            val level = if (args.isNotEmpty() && args[0].isString) args[0].asString() else "info"
            val msg = if (args.size > 1 && args[1].isString) args[1].asString() else ""
            val tagged = "[scev-rpc guest $machineUuid] $msg"
            when (level.lowercase()) {
                "trace" -> LOG.trace(tagged)
                "debug" -> LOG.debug(tagged)
                "warn" -> LOG.warn(tagged)
                "error" -> LOG.error(tagged)
                else -> LOG.info(tagged)
            }
            MsgValue.NIL
        }

        // `self` — machine-scoped environment info. Deliberately omits
        // anything that would identify the host mod or the machine's
        // world location: the scev compat layer presents as a generic
        // CC-style computer to the guest, and the guest has no need
        // to know which dimension or coordinates it's running in
        // (both can change under it; neither is load-bearing for
        // scripting; leaking them isn't worth it).
        //
        // Present: id (stable-ish), mc_version, cc_version (null when
        // CC isn't loaded), facing (relative-side names need it).
        // Absent: host identity, dimension id, block position.
        d.register(RpcProtocol.METHOD_SELF) { _ -> self(machineUuid) }

        // CC-surface stubs — replaced by ScevCCHandlers.install when
        // CC: Tweaked is loaded. Without CC, the guest sees a clean
        // error instead of "unknown method".
        val notInstalled = RpcHandler { _ ->
            throw RpcHandler.RpcException(
                "CC: Tweaked is not installed on this server",
                RpcErrors.NOT_INSTALLED,
            )
        }
        d.register(RpcProtocol.METHOD_LIST, notInstalled)
        d.register(RpcProtocol.METHOD_METHODS, notInstalled)
        d.register(RpcProtocol.METHOD_CALL, notInstalled)
        d.register(RpcProtocol.METHOD_QUEUE_EVENT, notInstalled)
        d.register(RpcProtocol.METHOD_SUBSCRIBE, notInstalled)
        d.register(RpcProtocol.METHOD_UNSUBSCRIBE, notInstalled)
        d.register(RpcProtocol.METHOD_DESCRIBE, notInstalled)
        d.register(RpcProtocol.METHOD_SCHEMA, notInstalled)
        d.register(RpcProtocol.METHOD_TYPE, notInstalled)
        d.register(RpcProtocol.METHOD_TRACE, notInstalled)
    }

    /**
     * `self` RPC — safe environment info for the guest.
     *
     * `id` is derived from the machine UUID's low 31 bits. It's the
     * same value [dan200.computercraft.api.peripheral.IComputerAccess]
     * sees (`ScevCCComputer.getID()` uses the same formula) so the
     * guest can use it to correlate RPC-side state with CC-side
     * identifiers.
     *
     * `mc_version` is read straight from Minecraft's `SharedConstants`
     * since we're already on the server main thread.
     *
     * `cc_version` comes from [ModList] when CC is present, null
     * otherwise. Nothing here dereferences CC-typed symbols — the only
     * string comparison is against a literal mod-id.
     *
     * `facing` is pulled from the machine's directional block state
     * (if the machine has been given a location — headless test
     * machines don't, and `facing` is then "unknown"). It's useful
     * because relative peripheral side names (`front`, `left`, `right`,
     * `back`) depend on it.
     */
    @JvmStatic
    private fun self(machineUuid: UUID): MsgValue {
        val m = LinkedHashMap<MsgValue, MsgValue>()
        // Matches ScevCCComputer.getID()'s derivation so guests can
        // cross-reference if they care.
        m[MsgValue.of("id")] = MsgValue.of(machineUuid.hashCode().ushr(1).toLong())
        m[MsgValue.of("label")] = MsgValue.of("scev_" + machineUuid.toString().take(8))
        m[MsgValue.of("mc_version")] = runCatching {
            SharedConstants.getCurrentVersion().name
        }.getOrDefault("unknown").let { MsgValue.of(it) }

        val ccVersion: String? = runCatching {
            val modList = ModList.get()
            if (modList.isLoaded("computercraft")) {
                modList.getModContainerById("computercraft")
                    .map { it.modInfo.version.toString() }
                    .orElse(null)
            } else {
                null
            }
        }.getOrNull()
        m[MsgValue.of("cc_version")] = ccVersion?.let { MsgValue.of(it) } ?: MsgValue.NIL

        // Protocol surface advertisement. Bumped on any breaking
        // change to the wire shape; guests gate compatibility on this.
        // Capability flags name optional features the host implements
        // — guests check the flag rather than the version because
        // forward-compat additions land outside of major bumps.
        // `limits` carries the wire-level numbers a guest needs to
        // budget its own requests (frame cap, chunk store sizing).
        m[MsgValue.of("protocol_version")] = MsgValue.of(PROTOCOL_VERSION)
        m[MsgValue.of("capabilities")] = MsgValue.ofMap(capabilitiesMap())
        m[MsgValue.of("limits")] = MsgValue.ofMap(limitsMap())

        m[MsgValue.of("facing")] = runCatching {
            val state = MachineManager.getMachineState(machineUuid)
            val level = state?.level
            val pos = state?.pos
            if (level != null && pos != null) {
                val bs = level.getBlockState(pos)
                bs.getValue(DirectionalBlock.FACING).serializedName
            } else {
                "unknown"
            }
        }.getOrDefault("unknown").let { MsgValue.of(it) }

        return MsgValue.ofMap(m)
    }

    /**
     * `read_chunk(streamId: int, offset: int, max_len: int) -> bin`
     *
     * Returns a binary slice of an oversized Response cached in the
     * per-machine [ChunkStore]. The guest stitches successive slices
     * (offset += returned.length) until it has `total_size` bytes,
     * then decodes locally as if the original Response had landed in
     * one frame. An empty bytes return at offset == size signals EOF.
     *
     * Error: [RpcErrors.NO_SUCH_PEER] when the stream isn't present —
     * could mean evicted (cap pressure or new chunked response pushed
     * the old one out), expired (TTL), or already discarded. Caller
     * should re-issue the original request.
     */
    @JvmStatic
    private fun readChunk(store: ChunkStore, args: List<MsgValue>): MsgValue {
        val streamId = requireLong(args, 0, "streamId")
        val offset = requireLong(args, 1, "offset")
        val maxLen = requireLong(args, 2, "max_len").toInt().coerceAtLeast(0)
        val slice = store.read(streamId, offset, maxLen)
            ?: throw RpcHandler.RpcException(
                "unknown chunk stream: $streamId",
                RpcErrors.NO_SUCH_PEER,
            )
        return MsgValue.of(slice)
    }

    /**
     * `batch(items: array, opts?: map) -> array`
     *
     * Runs `items` (each a `[method, args]` pair, args optional) one
     * at a time on this same dispatcher. Returns one envelope per
     * input item in the same order, regardless of which items
     * succeeded or failed:
     *
     *   `[err_or_nil, result_or_nil]`
     *
     * where `err` matches the [RpcFrame.Response] error shape:
     * `nil` on success, `{code, message}` on failure.
     *
     * Optional `opts.stop_on_error` (default `false`): when true, the
     * first errored item halts dispatch — every subsequent slot in
     * the result array carries `[{code: SKIPPED, …}, nil]` so the
     * guest can distinguish skipped items from real failures and the
     * length of the response always matches the input.
     *
     * Items dispatch serially in the same coroutine — equivalent to
     * the guest issuing N separate RPCs back-to-back, but with one
     * round-trip's worth of framing overhead. Methods that suspend
     * (yielding peripheral calls, future cancellable handlers) work
     * naturally because suspension yields the coroutine and the
     * batch resumes on the next dispatch step.
     *
     * Nested `batch` calls are refused with [RpcErrors.UNSUPPORTED];
     * a guest that wants nested-style fan-out should issue separate
     * batches.
     */
    @JvmStatic
    private suspend fun batch(d: RpcDispatcher, args: List<MsgValue>): MsgValue {
        val items = requireArray(args, 0, "items")
        val stopOnError: Boolean = if (args.size > 1 && args[1].isMap) {
            val opts = args[1].asMap()
            (opts[MsgValue.of("stop_on_error")] as? MsgValue.Bool)?.value ?: false
        } else {
            false
        }

        val out = ArrayList<MsgValue>(items.size)
        var halted = false
        for ((idx, item) in items.withIndex()) {
            if (halted) {
                out += batchEnvelope(
                    RpcFrame.ErrorInfo(RpcErrors.SKIPPED, "skipped after earlier item errored"),
                    MsgValue.NIL,
                )
                continue
            }
            val pair = (item as? MsgValue.Arr)?.value
            if (pair == null || pair.isEmpty() || !pair[0].isString) {
                out += batchEnvelope(
                    RpcFrame.ErrorInfo(RpcErrors.BAD_ARGS, "items[$idx] must be [method, args]"),
                    MsgValue.NIL,
                )
                if (stopOnError) halted = true
                continue
            }
            val method = pair[0].asString()
            if (method == RpcProtocol.METHOD_BATCH || method == RpcProtocol.METHOD_BATCH_PAR) {
                out += batchEnvelope(
                    RpcFrame.ErrorInfo(RpcErrors.UNSUPPORTED, "nested batch not allowed"),
                    MsgValue.NIL,
                )
                if (stopOnError) halted = true
                continue
            }
            val itemArgs: List<MsgValue> = if (pair.size > 1 && pair[1].isArray) {
                pair[1].asArray()
            } else {
                emptyList()
            }
            // Dispatch via the existing dispatcher so we get all the
            // standard error wrapping (RpcException -> code+message,
            // RuntimeException -> internal_error, unknown method ->
            // no_such_method) without re-implementing it here. Id is
            // a placeholder — the guest never sees it because we
            // strip the Response envelope down to (err, result).
            val resp = d.dispatch(RpcFrame.Request(0L, method, itemArgs))
            out += batchEnvelope(resp.error, resp.result)
            if (resp.error != null && stopOnError) halted = true
        }
        return MsgValue.ofArray(out)
    }

    /**
     * `batch_par(items: array, opts?: map) -> array`
     *
     * Same envelope as [batch], but items dispatch concurrently.
     * Cross-peripheral fan-out happens for free (per-peer mutex on
     * the host serialises same-peer calls — peripheral impls aren't
     * reentrant — while different peers proceed in parallel).
     *
     * Every item always runs: there's no meaningful "halt early"
     * once the fan-out is launched, so [batch]'s `stop_on_error`
     * doesn't apply. Errors per-item still surface with their codes
     * via the same `[err_or_nil, result]` envelope as `batch`.
     *
     * Nested `batch` / `batch_par` are refused with [RpcErrors.UNSUPPORTED]
     * so the fan-out doesn't recurse into a thread-pool storm.
     */
    @JvmStatic
    private suspend fun batchPar(d: RpcDispatcher, args: List<MsgValue>): MsgValue {
        val items = requireArray(args, 0, "items")
        return coroutineScope {
            val deferred = items.mapIndexed { idx, item ->
                async { dispatchOneBatchItem(d, idx, item) }
            }
            MsgValue.ofArray(deferred.map { it.await() })
        }
    }

    /**
     * Dispatch one item of a batch (ordered or parallel) and wrap
     * the outcome in the standard `[err_or_nil, result]` envelope.
     * Pulled out so [batch] and [batchPar] share the validation +
     * dispatch path.
     */
    private suspend fun dispatchOneBatchItem(
        d: RpcDispatcher,
        idx: Int,
        item: MsgValue,
    ): MsgValue {
        val pair = (item as? MsgValue.Arr)?.value
        if (pair == null || pair.isEmpty() || !pair[0].isString) {
            return batchEnvelope(
                RpcFrame.ErrorInfo(RpcErrors.BAD_ARGS, "items[$idx] must be [method, args]"),
                MsgValue.NIL,
            )
        }
        val method = pair[0].asString()
        if (method == RpcProtocol.METHOD_BATCH || method == RpcProtocol.METHOD_BATCH_PAR) {
            return batchEnvelope(
                RpcFrame.ErrorInfo(RpcErrors.UNSUPPORTED, "nested batch not allowed"),
                MsgValue.NIL,
            )
        }
        val itemArgs: List<MsgValue> = if (pair.size > 1 && pair[1].isArray) {
            pair[1].asArray()
        } else {
            emptyList()
        }
        val resp = d.dispatch(RpcFrame.Request(0L, method, itemArgs))
        return batchEnvelope(resp.error, resp.result)
    }

    private fun batchEnvelope(err: RpcFrame.ErrorInfo?, result: MsgValue): MsgValue {
        val errSlot: MsgValue = if (err == null) {
            MsgValue.NIL
        } else {
            MsgValue.ofMap(
                linkedMapOf(
                    MsgValue.of("code") to MsgValue.of(err.code),
                    MsgValue.of("message") to MsgValue.of(err.message),
                )
            )
        }
        return MsgValue.ofArray(listOf(errSlot, result))
    }

    /**
     * `discard_chunk(streamId: int) -> bool`
     *
     * Drop an in-flight chunked response early. Returns whether the
     * stream existed; idempotent on already-discarded ids. No error
     * for unknown streams — discard is best-effort cleanup.
     */
    @JvmStatic
    private fun discardChunk(store: ChunkStore, args: List<MsgValue>): MsgValue {
        val streamId = requireLong(args, 0, "streamId")
        return MsgValue.of(store.discard(streamId))
    }

    @Throws(RpcHandler.RpcException::class)
    private fun requireLong(args: List<MsgValue>, idx: Int, name: String): Long {
        if (args.size <= idx || !args[idx].isInt) {
            throw RpcHandler.RpcException("expected integer argument: $name", RpcErrors.BAD_ARGS)
        }
        return args[idx].asInt()
    }

    /** Convenience for the first arg being a string. */
    @JvmStatic
    @Throws(RpcHandler.RpcException::class)
    fun requireString(args: List<MsgValue>, idx: Int, name: String): String {
        if (args.size <= idx || !args[idx].isString) {
            throw RpcHandler.RpcException("expected string argument: $name", RpcErrors.BAD_ARGS)
        }
        return args[idx].asString()
    }

    /** Convenience for array args. */
    @JvmStatic
    @Throws(RpcHandler.RpcException::class)
    fun requireArray(args: List<MsgValue>, idx: Int, name: String): List<MsgValue> {
        if (args.size <= idx || !args[idx].isArray) {
            throw RpcHandler.RpcException("expected array argument: $name", RpcErrors.BAD_ARGS)
        }
        return args[idx].asArray()
    }
}
