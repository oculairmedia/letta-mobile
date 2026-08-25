package com.letta.mobile.data.model.provider

import com.letta.mobile.data.model.CatalogRevision
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderFieldId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.ProviderRevision
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProviderDomainIdsTest {

    @Test
    fun typedIdsRetainExactValueAndToString() {
        val hostId = HostId("host-1")
        val defId = ProviderDefinitionId("openai")
        val instanceId = ProviderInstanceId("inst-openai-1")
        val fieldId = ProviderFieldId("api_key")
        val routeId = ModelRouteId("route-gpt-4o")
        val catalogRev = CatalogRevision("cat-rev-1")
        val providerRev = ProviderRevision("prov-rev-1")

        assertEquals("host-1", hostId.value)
        assertEquals("host-1", hostId.toString())

        assertEquals("openai", defId.value)
        assertEquals("openai", defId.toString())

        assertEquals("inst-openai-1", instanceId.value)
        assertEquals("inst-openai-1", instanceId.toString())

        assertEquals("api_key", fieldId.value)
        assertEquals("api_key", fieldId.toString())

        assertEquals("route-gpt-4o", routeId.value)
        assertEquals("route-gpt-4o", routeId.toString())

        assertEquals("cat-rev-1", catalogRev.value)
        assertEquals("cat-rev-1", catalogRev.toString())

        assertEquals("prov-rev-1", providerRev.value)
        assertEquals("prov-rev-1", providerRev.toString())
    }

    @Test
    fun typedIdsWithSameUnderlyingValueAreDistinctTypes() {
        val raw = "identifier-abc"
        val hostId = HostId(raw)
        val defId = ProviderDefinitionId(raw)
        val instanceId = ProviderInstanceId(raw)
        val fieldId = ProviderFieldId(raw)
        val routeId = ModelRouteId(raw)

        // Type safety: separate value classes must not equate across distinct types
        assertNotEquals<Any>(hostId, defId)
        assertNotEquals<Any>(defId, instanceId)
        assertNotEquals<Any>(instanceId, fieldId)
        assertNotEquals<Any>(fieldId, routeId)
    }

    @Test
    fun typedIdsSerializeAsDirectStrings() {
        val json = Json { prettyPrint = false }

        assertEquals("\"host-primary\"", json.encodeToString(HostId("host-primary")))
        assertEquals("\"openai\"", json.encodeToString(ProviderDefinitionId("openai")))
        assertEquals("\"inst-1\"", json.encodeToString(ProviderInstanceId("inst-1")))
        assertEquals("\"api_key\"", json.encodeToString(ProviderFieldId("api_key")))
        assertEquals("\"route-1\"", json.encodeToString(ModelRouteId("route-1")))
        assertEquals("\"rev-100\"", json.encodeToString(CatalogRevision("rev-100")))
        assertEquals("\"rev-200\"", json.encodeToString(ProviderRevision("rev-200")))

        assertEquals(HostId("host-primary"), json.decodeFromString<HostId>("\"host-primary\""))
        assertEquals(ProviderDefinitionId("openai"), json.decodeFromString<ProviderDefinitionId>("\"openai\""))
        assertEquals(ProviderInstanceId("inst-1"), json.decodeFromString<ProviderInstanceId>("\"inst-1\""))
        assertEquals(ProviderFieldId("api_key"), json.decodeFromString<ProviderFieldId>("\"api_key\""))
        assertEquals(ModelRouteId("route-1"), json.decodeFromString<ModelRouteId>("\"route-1\""))
        assertEquals(CatalogRevision("rev-100"), json.decodeFromString<CatalogRevision>("\"rev-100\""))
        assertEquals(ProviderRevision("rev-200"), json.decodeFromString<ProviderRevision>("\"rev-200\""))
    }
}
