package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderWireSerializationTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun providerDefinitionsListRoundTrip() {
        val dto = ProviderDefinitionsListResponseDto(
            contractVersion = 1,
            definitions = listOf(
                ProviderDefinitionDto(
                    id = "openai",
                    displayName = "OpenAI",
                    description = "OpenAI platform",
                    supportedProtocols = listOf("openai"),
                    fields = listOf(
                        ProviderFieldSchemaDto(
                            id = "api_key",
                            label = "API Key",
                            isSecret = true,
                            isRequired = true,
                        ),
                    ),
                    defaultBaseUrl = "https://api.openai.com/v1",
                ),
            ),
        )

        val encoded = json.encodeToString(dto)
        assertTrue(encoded.contains("\"contract_version\":1"))
        assertTrue(encoded.contains("\"display_name\":\"OpenAI\""))
        assertTrue(encoded.contains("\"is_secret\":true"))

        val decoded = json.decodeFromString<ProviderDefinitionsListResponseDto>(encoded)
        assertEquals(dto, decoded)
    }

    @Test
    fun redactedProviderInstancesListRoundTrip() {
        val dto = ProviderInstancesListResponseDto(
            contractVersion = 1,
            hostId = "host-primary",
            instances = listOf(
                RedactedProviderInstanceDto(
                    id = "inst-1",
                    hostId = "host-primary",
                    definitionId = "openai",
                    displayName = "Primary OpenAI",
                    baseUrl = "https://api.openai.com/v1",
                    credentialStatus = "configured",
                    operationalStatus = "active",
                    revision = "rev-1",
                    configuredFieldIds = listOf("api_key"),
                    configuredHeaderNames = listOf("X-Custom-Header"),
                ),
            ),
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<ProviderInstancesListResponseDto>(encoded)
        assertEquals(dto, decoded)
    }

    @Test
    fun modelRoutesListRoundTrip() {
        val dto = ModelRoutesListResponseDto(
            contractVersion = 1,
            hostId = "host-primary",
            routes = listOf(
                ModelRouteDto(
                    id = "route-gpt-4o",
                    hostId = "host-primary",
                    providerInstanceId = "inst-1",
                    modelHandle = "gpt-4o",
                    displayName = "GPT-4o",
                    contextWindowLimit = 128000,
                    availability = "available",
                    visibility = "visible",
                    aliases = listOf("gpt-4o-2024-08-06"),
                    revision = "cat-rev-1",
                ),
            ),
        )

        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString<ModelRoutesListResponseDto>(encoded)
        assertEquals(dto, decoded)
    }

    @Test
    fun revisionsRoundTrip() {
        val catRev = CatalogRevisionDto(hostId = "host-primary", revision = "rev-42")
        val provRev = ProviderRevisionDto(instanceId = "inst-1", revision = "rev-99")

        val encCat = json.encodeToString(catRev)
        val encProv = json.encodeToString(provRev)

        assertEquals(catRev, json.decodeFromString<CatalogRevisionDto>(encCat))
        assertEquals(provRev, json.decodeFromString<ProviderRevisionDto>(encProv))
    }
}
