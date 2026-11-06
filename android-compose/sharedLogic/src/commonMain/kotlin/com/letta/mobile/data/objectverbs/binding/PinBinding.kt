package com.letta.mobile.data.objectverbs.binding

import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.VerbContext
import com.letta.mobile.data.objectverbs.VerbInvocation
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.registry.CoreVerbProvider
import com.letta.mobile.data.objectverbs.registry.VerbRegistry
import com.letta.mobile.data.pins.DevicePinStore
import com.letta.mobile.data.pins.LegacyPinSources
import com.letta.mobile.data.pins.PinJournal
import com.letta.mobile.data.pins.ScopedObjectRef
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.flow.DerivedStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One trusted session's view of the device store. No legacy entry is assigned to this source. */
class PinBinding(val store: DevicePinStore, val source: StateFlow<String?>) {
    val registry: VerbRegistry
        get() {
            val namespace = source.value
            return VerbRegistry(CoreVerbProvider(store, { namespace }) { ref ->
                source.value == namespace && LegacyPinSources.objectRef("${ref.kind}:${ref.id}") == ref
            })
        }
    val references: StateFlow<List<ObjectRef>> = DerivedStateFlow(
        combine(store.journal, source) { journal, namespace -> visible(journal, namespace) },
        { visible(store.journal.value, source.value) },
    )

    fun ids(kind: String): StateFlow<List<String>> = DerivedStateFlow(
        combine(references, source) { refs, _ -> refs.filter { it.kind == kind }.map { it.id } },
        { references.value.filter { it.kind == kind }.map { it.id } },
    )

    fun setPinned(ref: ObjectRef, pinned: Boolean): VerbOutcome = registry.invoke(
        VerbInvocation(ref, CoreVerbProvider.PIN, VerbContext("adapter"), buildJsonObject { put("pinned", pinned) }),
    )

    fun reorder(refs: List<ObjectRef>) = store.reorder(refs.mapNotNull(::scoped))

    fun scoped(ref: ObjectRef): ScopedObjectRef? = when (ref.kind) {
        "shortcut" -> ScopedObjectRef(ScopedObjectRef.DEVICE_SOURCE, ref)
        else -> source.value?.let { ScopedObjectRef(it, ref) }
    }

    private fun visible(journal: PinJournal, namespace: String?): List<ObjectRef> = journal.pins
        .filter { it.source == namespace || it.source == ScopedObjectRef.DEVICE_SOURCE }.map { it.objectRef }
}

/**
 * Device settings composition root. Create once per application/tab and pass this facade to every
 * pin adapter. Ownership ends with the settings instance; no process registry retains old devices.
 * All persistence, including confirmed writes, delegates to the real host backing.
 */
class DevicePinSettingsStore(backing: SecureSettingsStore) : SecureSettingsStore by backing {
    internal val pins: DevicePinStore by lazy { DevicePinStore(backing) }
}

/** Attach the pin owner once at the settings composition root; already-owned ports retain identity. */
fun SecureSettingsStore.withDevicePins(): SecureSettingsStore =
    if (this is DevicePinSettingsStore) this else DevicePinSettingsStore(this)

/** Stateless access to the one settings-owned journal; sessions share state, never source bindings. */
object DevicePins {
    fun store(backing: SecureSettingsStore): DevicePinStore {
        val pins = requireNotNull((backing as? DevicePinSettingsStore)?.pins) {
            "Bind the device settings owner with withDevicePins before constructing pin adapters"
        }
        pins.tryImportLegacy(LegacyPinSources.secure(backing, setOf(
            LegacyPinSources.HOME_PRODUCTION, LegacyPinSources.HOME_DEFAULT,
            "conversations.pinned", "projects.pinned", "desktop.projects.pinned",
        )))
        return pins
    }

    fun bind(backing: SecureSettingsStore, source: StateFlow<String?> = MutableStateFlow(null)): PinBinding =
        PinBinding(store(backing), source)
}
