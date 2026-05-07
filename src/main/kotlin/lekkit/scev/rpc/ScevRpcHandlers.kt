/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

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

        // Wire-level limits the guest needs to know to budget its own
        // request shapes. `frame_max_bytes` is the max plaintext (pre-
        // COBS) MessagePack payload either side will accept; bigger
        // responses come back as a FRAME_TOO_LARGE error instead of
        // landing on the wire. Useful for guests that decide between
        // paged or unpaged describe queries.
        m[MsgValue.of("frame_max_bytes")] = MsgValue.of(ScevRpcManager.MAX_FRAME_BYTES.toLong())

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
