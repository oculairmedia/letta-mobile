package com.letta.mobile.data.pins

import com.letta.mobile.data.objectverbs.binding.withDevicePins

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.VerbContext
import com.letta.mobile.data.objectverbs.VerbInvocation
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.binding.DevicePins
import com.letta.mobile.data.objectverbs.binding.PinBinding
import com.letta.mobile.data.objectverbs.registry.CoreVerbProvider
import com.letta.mobile.data.objectverbs.registry.VerbRegistry
import com.letta.mobile.data.repository.CachedSettingsRepository
import com.letta.mobile.data.session.ANDROID_REMOTE_LETTA_ID_PREFIX
import com.letta.mobile.data.session.remoteLettaBackendDescriptor
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.storage.MutableSettingsPreferencesEditor
import com.letta.mobile.data.storage.SettingsPreferencesSnapshot
import com.letta.mobile.data.storage.SettingsPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PinRegistryTest {
    @Test
    fun blankConfigIdUsesSessionNamespaceForSettingsAndHome() = runTest {
        val backing = InMemorySecureSettingsStore().withDevicePins()
        val settings = CachedSettingsRepository(preferences(flowOf(emptySnapshot)), backing)
        val config = LettaConfig(id = " ", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://example.invalid")
        settings.saveConfig(config)
        val namespace = remoteLettaBackendDescriptor(config, ANDROID_REMOTE_LETTA_ID_PREFIX).backendId.value
        val home = com.letta.mobile.data.home.SettingsStoreHomePinStore(backing, emptyList(), source = MutableStateFlow(namespace))

        settings.setAgentPinned("a", true)
        assertEquals("remote-letta:default", namespace)
        assertEquals(listOf("agent:a"), home.pinnedKeys.value)
    }

    @Test
    fun suspendedImportCannotRedirectPinToReplacementConfig() = runTest {
        val snapshot = CompletableDeferred<SettingsPreferencesSnapshot>()
        val backing = InMemorySecureSettingsStore().withDevicePins()
        val settings = CachedSettingsRepository(preferences(flow { emit(snapshot.await()) }), backing)
        fun config(id: String) = LettaConfig(id = id, mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://example.invalid")
        settings.saveConfig(config("one"))
        val write = async(start = CoroutineStart.UNDISPATCHED) { runCatching { settings.setAgentPinned("a", true) } }
        settings.saveConfig(config("two"))
        snapshot.complete(emptySnapshot)

        assertTrue(write.await().isFailure)
        assertEquals(emptyList(), settings.objectPins.store.journal.value.pins)
    }

    @Test
    fun staleRegistryCannotWriteToAReplacementBackend() {
        val store = DevicePinStore(InMemorySecureSettingsStore().withDevicePins())
        val source = MutableStateFlow<String?>("one")
        val registry = PinBinding(store, source).registry
        val request = VerbInvocation(ObjectRef("agent", "a"), "core.pin", VerbContext("rail"),
            buildJsonObject { put("pinned", true) })
        source.value = "two"
        assertIs<VerbOutcome.Unavailable>(registry.invoke(request))
        assertEquals(emptyList(), store.journal.value.pins)
    }

    @Test
    fun registryRechecksObjectAndDesiredStateAndAllLensesSeeTheSameFacets() {
        val store = DevicePinStore(InMemorySecureSettingsStore().withDevicePins())
        val ref = ObjectRef("agent", "a")
        var present = true
        val registry = VerbRegistry(CoreVerbProvider(store, { "source" }) { it == ref && present })
        val rail = VerbContext("rail")
        val request = VerbInvocation(ref, "core.pin", rail, buildJsonObject { put("pinned", true) })
        assertEquals(false, registry.describe(ref, rail)?.facets?.pinned)
        assertIs<VerbOutcome.Updated>(registry.invoke(request))
        assertIs<VerbOutcome.Updated>(registry.invoke(request))
        assertEquals(1, store.journal.value.pins.size)
        assertEquals(registry.describe(ref, rail)?.facets, registry.describe(ref, VerbContext("home-tile"))?.facets)
        assertEquals("Unpin agent", registry.describe(ref, rail)?.verbs?.single()?.label)
        assertEquals(0, registry.describe(ref, rail)?.facets?.pinOrder)
        present = false
        assertIs<VerbOutcome.Unavailable>(registry.invoke(request.copy(input = buildJsonObject { put("pinned", false) })))
        assertEquals(1, store.journal.value.pins.size)
        assertNull(registry.describe(ref, rail))
        assertIs<VerbOutcome.Unavailable>(VerbRegistry(null).invoke(request))
        assertIs<VerbOutcome.Unavailable>(registry.invoke(request.copy(verbId = "future.pin")))
    }

    @Test
    fun homeAgentWriterAndRailBindingShareOneStore() {
        val backing = InMemorySecureSettingsStore().withDevicePins()
        val source = MutableStateFlow<String?>("backend")
        val home = com.letta.mobile.data.home.SettingsStoreHomePinStore(backing, emptyList(), source = source)
        val rail = DevicePins.bind(backing, source)
        home.setPinned("agent:a", true)
        assertEquals(listOf("a"), rail.ids("agent").value)
        assertIs<VerbOutcome.Updated>(rail.setPinned(ObjectRef("agent", "a"), false))
        assertEquals(emptyList(), home.pinnedKeys.value)
    }

    @Test
    fun missingOrMalformedInputAndUnknownKindsCannotWrite() {
        val store = DevicePinStore(InMemorySecureSettingsStore().withDevicePins())
        val registry = VerbRegistry(CoreVerbProvider(store, { "backend" }) { true })
        val request = VerbInvocation(ObjectRef("agent", "a"), "core.pin", VerbContext("rail"))
        assertIs<VerbOutcome.InvalidInput>(registry.invoke(request))
        assertIs<VerbOutcome.InvalidInput>(registry.invoke(request.copy(input = buildJsonObject { put("pinned", "true") })))
        assertIs<VerbOutcome.Unavailable>(registry.invoke(request.copy(objectRef = ObjectRef("future", "a"))))
        assertEquals(emptyList(), store.journal.value.pins)
    }

    private val emptySnapshot = object : SettingsPreferencesSnapshot {
        override fun getString(key: String): String? = null
        override fun getBoolean(key: String): Boolean? = null
        override fun getFloat(key: String): Float? = null
        override fun getStringSet(key: String): Set<String>? = null
    }

    private fun preferences(values: Flow<SettingsPreferencesSnapshot>) = object : SettingsPreferencesStore {
        override val snapshots = values
        override suspend fun edit(block: suspend (MutableSettingsPreferencesEditor) -> Unit) = error("Unexpected edit")
        override suspend fun clearAll() = error("Unexpected clear")
    }
}
