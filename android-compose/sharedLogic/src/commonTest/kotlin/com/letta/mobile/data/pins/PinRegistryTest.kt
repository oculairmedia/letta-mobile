package com.letta.mobile.data.pins

import com.letta.mobile.data.objectverbs.binding.withDevicePins

import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.VerbContext
import com.letta.mobile.data.objectverbs.VerbInvocation
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.binding.DevicePins
import com.letta.mobile.data.objectverbs.binding.PinBinding
import com.letta.mobile.data.objectverbs.registry.CoreVerbProvider
import com.letta.mobile.data.objectverbs.registry.VerbRegistry
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PinRegistryTest {
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
}
