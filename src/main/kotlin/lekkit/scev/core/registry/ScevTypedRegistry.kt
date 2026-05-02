/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.core.registry

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import org.slf4j.Logger

/**
 * Tiny shared base for the plain-singleton scev registries that hold a
 * [LinkedHashMap] of [ResourceLocation]-keyed entries.
 *
 * Used by [lekkit.scev.machine.firmware.FirmwareRegistry] and
 * [lekkit.scev.machine.storage.DiskTemplateRegistry], which used to be
 * two near-identical 100-line files. Each subclass is a Kotlin
 * `object` extending this and adds short `@JvmStatic` shims that
 * forward to the inherited methods so existing Java callers keep
 * `FirmwareRegistry.get(...)` working without threading through
 * `INSTANCE`.
 *
 * **Threading.** All access is synchronized on the subclass instance.
 * Registration happens at mod init (single-threaded); lookups happen
 * per-machine on the tick thread; contention is nil in practice.
 *
 * **Validation.** Subclasses provide [validate] to enforce
 * domain-specific invariants (firmwares need a non-empty payload list,
 * disk templates need a non-empty asset name + positive size).
 * Failing the check throws — registries are populated at mod init,
 * not by user data, so an [IllegalArgumentException] surfaces as a
 * crash report at FMLCommonSetupEvent which is the right blast radius.
 */
abstract class ScevTypedRegistry<T : Any> {
    protected val log: Logger = LogUtils.getLogger()
    private val entries: MutableMap<ResourceLocation, T> = LinkedHashMap()

    /**
     * Subclasses override to enforce their own invariants on a fresh
     * registration. Throw [IllegalArgumentException] (or use
     * [require]) on rejection.
     */
    protected abstract fun validate(id: ResourceLocation, value: T)

    /**
     * Register (or attempt to re-register) [value] under [id].
     * Duplicate registrations log a warning and keep the first one,
     * matching Forge's DeferredRegister semantics.
     */
    @Synchronized
    fun register(id: ResourceLocation, value: T) {
        validate(id, value)
        val prior = entries.putIfAbsent(id, value)
        if (prior != null) {
            log.warn("Duplicate {} registration for {}: keeping {}, ignoring {}",
                kind, id, prior.javaClass.simpleName, value.javaClass.simpleName)
        } else {
            log.debug("Registered {} {} -> {}", kind, id, value.javaClass.simpleName)
        }
    }

    /** Retrieve the entry registered under [id], or `null`. */
    @Synchronized
    fun lookup(id: ResourceLocation?): T? = if (id == null) null else entries[id]

    /** Does this id resolve to a registered entry? */
    @Synchronized
    fun has(id: ResourceLocation?): Boolean = id != null && entries.containsKey(id)

    /** All registered ids, in registration order. Defensive copy. */
    @Synchronized
    fun ids(): Collection<ResourceLocation> = entries.keys.toList()

    /** Current registration count. */
    @Synchronized
    fun size(): Int = entries.size

    /**
     * Test-only: clear all registrations. Unit tests that register
     * custom entries must reset state between test methods so
     * ordering doesn't leak side effects.
     */
    @Synchronized
    fun clearForTests() {
        entries.clear()
    }

    /**
     * Short label used in log messages — "firmware",
     * "disk template", etc. Subclasses override; default uses the
     * class simple name lowercased.
     */
    protected open val kind: String
        get() = javaClass.simpleName.lowercase()
}
