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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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

/** Device-lifetime owners, keyed by the host's storage object. Sessions share state, never source bindings. */
object DevicePins {
    private val lock = SynchronizedObject()
    private val owners = mutableMapOf<SecureSettingsStore, DevicePinStore>()

    fun store(backing: SecureSettingsStore): DevicePinStore = synchronized(lock) {
        owners.getOrPut(backing) { DevicePinStore(backing) }.also { pins ->
                pins.tryImportLegacy(LegacyPinSources.secure(backing, setOf(
                    LegacyPinSources.HOME_PRODUCTION, LegacyPinSources.HOME_DEFAULT,
                    "conversations.pinned", "projects.pinned", "desktop.projects.pinned",
                )))
        }
    }

    fun bind(backing: SecureSettingsStore, source: StateFlow<String?> = MutableStateFlow(null)): PinBinding =
        PinBinding(store(backing), source)
}
