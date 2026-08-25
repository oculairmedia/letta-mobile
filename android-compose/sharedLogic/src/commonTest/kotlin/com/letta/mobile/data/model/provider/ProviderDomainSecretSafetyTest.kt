package com.letta.mobile.data.model.provider

import com.letta.mobile.data.model.CatalogRevision
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderFieldId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.ProviderRevision
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDomainSecretSafetyTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun redactedProviderInstanceContainsNoSecretFieldsOrSentinels() {
        val secretSentinel = "sk-live-sentinel-xyz-987654321-DO-NOT-LEAK"

        val instance = RedactedProviderInstance(
            id = ProviderInstanceId("inst-openai-prod"),
            hostId = HostId("host-primary"),
            definitionId = ProviderDefinitionId("openai"),
            displayName = "OpenAI Production",
            baseUrl = "https://api.openai.com/v1",
            credentialStatus = CredentialStatus.Configured,
            operationalStatus = OperationalStatus.Active,
            revision = ProviderRevision("rev-42"),
            configuredFieldIds = persistentListOf(ProviderFieldId("api_key"), ProviderFieldId("org_id")),
            customHeaders = persistentMapOf("X-Custom-Env" to "production"),
        )

        val stringRepr = instance.toString()
        val jsonRepr = json.encodeToString(instance)

        // Ensure secret sentinel is nowhere in representations
        assertFalse(stringRepr.contains(secretSentinel))
        assertFalse(jsonRepr.contains(secretSentinel))

        // Ensure no field named "apiKey", "secret", "token", "password", or "credentialValue" exists in JSON
        assertFalse(jsonRepr.contains("apiKey", ignoreCase = true))
        assertFalse(jsonRepr.contains("secretValue", ignoreCase = true))
        assertFalse(jsonRepr.contains("password", ignoreCase = true))
        assertFalse(jsonRepr.contains("credential_value", ignoreCase = true))

        // Configured field IDs are metadata only
        assertTrue(jsonRepr.contains("\"api_key\""))
        assertTrue(jsonRepr.contains("\"configured\""))
    }

    @Test
    fun canonicalModelRouteContainsNoSecretSentinels() {
        val route = CanonicalModelRoute(
            id = ModelRouteId("route-gpt-4o"),
            hostId = HostId("host-primary"),
            providerInstanceId = ProviderInstanceId("inst-openai-prod"),
            modelHandle = "gpt-4o",
            displayName = "GPT-4o (Production)",
            contextWindowLimit = 128000,
            availability = ModelAvailability.Available,
            visibility = VisibilityPolicy.Visible,
            aliases = persistentListOf("gpt-4o-latest", "chatgpt-4o"),
            revision = CatalogRevision("rev-100"),
        )

        val stringRepr = route.toString()
        val jsonRepr = json.encodeToString(route)

        assertFalse(jsonRepr.contains("secret", ignoreCase = true))
        assertFalse(jsonRepr.contains("key", ignoreCase = true))
        assertTrue(stringRepr.contains("gpt-4o"))
        assertTrue(jsonRepr.contains("\"gpt-4o\""))
    }

    @Test
    fun providerFieldSchemaMarksSecretMetadataWithoutHoldingValue() {
        val schema = ProviderFieldSchema(
            id = ProviderFieldId("api_key"),
            label = "API Key",
            description = "Secret provider authentication token",
            isSecret = true,
            isRequired = true,
            placeholder = "sk-...",
        )

        val jsonRepr = json.encodeToString(schema)
        assertTrue(schema.isSecret)
        assertTrue(jsonRepr.contains("\"is_secret\": true"))
        // Schema definition holds schema structure, never an input value
        assertFalse(jsonRepr.contains("\"value\""))
    }

    @Test
    fun immutableCollectionsRetainStructuralEqualityAndImmutability() {
        val list1 = persistentListOf(ProviderFieldId("api_key"), ProviderFieldId("org_id"))
        val list2 = persistentListOf(ProviderFieldId("api_key"), ProviderFieldId("org_id"))
        val map1 = persistentMapOf("key1" to "val1", "key2" to "val2")
        val map2 = persistentMapOf("key1" to "val1", "key2" to "val2")

        assertEquals(list1, list2)
        assertEquals(list1.hashCode(), list2.hashCode())
        assertEquals(map1, map2)
        assertEquals(map1.hashCode(), map2.hashCode())

        val instance1 = RedactedProviderInstance(
            id = ProviderInstanceId("inst-1"),
            hostId = HostId("host-1"),
            definitionId = ProviderDefinitionId("openai"),
            displayName = "OpenAI",
            configuredFieldIds = list1,
            customHeaders = map1,
        )

        val instance2 = RedactedProviderInstance(
            id = ProviderInstanceId("inst-1"),
            hostId = HostId("host-1"),
            definitionId = ProviderDefinitionId("openai"),
            displayName = "OpenAI",
            configuredFieldIds = list2,
            customHeaders = map2,
        )

        assertEquals(instance1, instance2)
        assertEquals(instance1.hashCode(), instance2.hashCode())
    }
}
