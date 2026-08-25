package com.letta.mobile.data.model.provider

import com.letta.mobile.data.model.CatalogRevision
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderFieldId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.ProviderRevision
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDomainSecretSafetyTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val secretSentinel = "sk-live-sentinel-xyz-987654321-DO-NOT-LEAK"

    private fun providerInstance(
        baseUrl: String? = "https://api.openai.com/v1",
        headerNames: ImmutableList<String> = persistentListOf("OpenAI-Organization", "OpenAI-Project"),
    ) = RedactedProviderInstance(
        id = ProviderInstanceId("inst-openai-prod"),
        hostId = HostId("host-primary"),
        definitionId = ProviderDefinitionId("openai"),
        displayName = "OpenAI Production",
        baseUrl = baseUrl,
        credentialStatus = CredentialStatus.Configured,
        operationalStatus = OperationalStatus.Active,
        revision = ProviderRevision("rev-42"),
        configuredFieldIds = persistentListOf(ProviderFieldId("api_key"), ProviderFieldId("org_id")),
        configuredHeaderNames = headerNames,
    )

    @Test
    fun redactedProviderInstanceContainsOnlyMetadataAndNoSecretValues() {
        val instance = providerInstance()

        val encoded = json.encodeToString(instance)
        val stringified = instance.toString()

        // Assert JSON encoding contains only metadata
        assertTrue(encoded.contains("\"credential_status\":\"configured\""))
        assertTrue(encoded.contains("\"configured_field_ids\":[\"api_key\",\"org_id\"]"))
        assertTrue(encoded.contains("\"configured_header_names\":[\"OpenAI-Organization\",\"OpenAI-Project\"]"))

        // Assert secret sentinel is completely absent
        assertFalse(encoded.contains(secretSentinel))
        assertFalse(stringified.contains(secretSentinel))
        assertFalse(encoded.contains("bearer", ignoreCase = true))
        assertFalse(stringified.contains("bearer", ignoreCase = true))

        // Structural equality check
        val identicalCopy = instance.copy()
        assertEquals(instance, identicalCopy)
        assertEquals(instance.hashCode(), identicalCopy.hashCode())
    }

    @Test
    fun redactedProviderInstanceSerializationSurfaceHasNoSecretBearingFields() {
        val serializedFields = json.encodeToJsonElement(providerInstance()).jsonObject.keys

        assertEquals(
            setOf(
                "id",
                "host_id",
                "definition_id",
                "display_name",
                "base_url",
                "credential_status",
                "operational_status",
                "revision",
                "configured_field_ids",
                "configured_header_names",
            ),
            serializedFields,
        )
        assertFalse(serializedFields.any { it.contains("token", ignoreCase = true) })
        assertFalse(serializedFields.any { it.contains("value", ignoreCase = true) })
        assertFalse(serializedFields.any { it.contains("custom_headers", ignoreCase = true) })
    }

    @Test
    fun rejectsHeaderValuesWithoutLeakingThemInErrors() {
        val error = assertFailsWith<IllegalArgumentException> {
            providerInstance(headerNames = persistentListOf("Authorization: Bearer $secretSentinel"))
        }

        assertFalse(error.toString().contains(secretSentinel))
        assertEquals("Configured provider headers must contain names only", error.message)
    }

    @Test
    fun rejectsCredentialsEmbeddedInProviderUrlsWithoutLeakingThemInErrors() {
        val instanceError = assertFailsWith<IllegalArgumentException> {
            providerInstance(baseUrl = "https://service-account:$secretSentinel@example.com/v1")
        }
        val definitionError = assertFailsWith<IllegalArgumentException> {
            ProviderDefinition(
                id = ProviderDefinitionId("custom"),
                displayName = "Custom",
                defaultBaseUrl = "https://example.com/v1?api_key=$secretSentinel",
            )
        }

        assertFalse(instanceError.toString().contains(secretSentinel))
        assertFalse(definitionError.toString().contains(secretSentinel))
        assertEquals(
            "Provider base URL must not contain user info, query parameters, or a fragment",
            instanceError.message,
        )
        assertEquals(
            "Default provider base URL must not contain user info, query parameters, or a fragment",
            definitionError.message,
        )
    }

    @Test
    fun providerFieldSchemaOnlyCarriesInputSchemaMetadata() {
        val schema = ProviderFieldSchema(
            id = ProviderFieldId("api_key"),
            label = "API Key",
            description = "Your provider secret key",
            isSecret = true,
            isRequired = true,
            placeholder = "sk-...",
        )

        val encoded = json.encodeToString(schema)
        val stringified = schema.toString()

        // Schema records whether field is secret on input, but cannot hold a secret value itself
        assertTrue(encoded.contains("\"is_secret\":true"))
        assertFalse(encoded.contains(secretSentinel))
        assertFalse(stringified.contains(secretSentinel))
    }

    @Test
    fun providerDefinitionCarriesProtocolTypesAndNoSecrets() {
        val def = ProviderDefinition(
            id = ProviderDefinitionId("anthropic"),
            displayName = "Anthropic",
            description = "Claude models",
            supportedProtocols = persistentListOf(ProviderProtocol.Anthropic, ProviderProtocol.OpenAi),
            fields = persistentListOf(
                ProviderFieldSchema(
                    id = ProviderFieldId("api_key"),
                    label = "API Key",
                    isSecret = true,
                    isRequired = true,
                ),
            ),
            defaultBaseUrl = "https://api.anthropic.com",
        )

        val encoded = json.encodeToString(def)
        assertTrue(encoded.contains("\"supported_protocols\":[\"anthropic\",\"openai\"]"))
        assertFalse(encoded.contains(secretSentinel))
    }

    @Test
    fun canonicalModelRouteContainsNoSecretSentinels() {
        val route = CanonicalModelRoute(
            id = ModelRouteId("route-gpt-4o"),
            hostId = HostId("host-primary"),
            providerInstanceId = ProviderInstanceId("inst-openai-prod"),
            modelHandle = "gpt-4o",
            displayName = "GPT-4o",
            contextWindowLimit = 128000,
            availability = ModelAvailability.Available,
            visibility = VisibilityPolicy.Visible,
            aliases = persistentListOf("gpt-4o-2024-08-06", "gpt-4o-latest"),
            revision = CatalogRevision("cat-rev-1"),
        )

        val encoded = json.encodeToString(route)
        val stringified = route.toString()

        assertFalse(encoded.contains(secretSentinel))
        assertFalse(stringified.contains(secretSentinel))
        assertTrue(encoded.contains("\"visibility\":\"visible\""))
        assertTrue(encoded.contains("\"availability\":\"available\""))
    }
}
