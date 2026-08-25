package com.letta.mobile.data.model.provider.wire

import com.letta.mobile.data.model.CatalogRevision
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderFieldId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.ProviderRevision
import com.letta.mobile.data.model.provider.CredentialStatus
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.OperationalStatus
import com.letta.mobile.data.model.provider.ProviderProtocol
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProviderWireMapperTest {

    private val primaryHost = HostId("host-primary")

    @Test
    fun providerDefinitionRoundTripMapping() {
        val dto = ProviderDefinitionDto(
            id = "anthropic",
            displayName = "Anthropic",
            description = "Claude models",
            supportedProtocols = listOf("anthropic"),
            fields = listOf(
                ProviderFieldSchemaDto(
                    id = "api_key",
                    label = "API Key",
                    isSecret = true,
                    isRequired = true,
                ),
            ),
            defaultBaseUrl = "https://api.anthropic.com",
        )

        val domain = dto.toDomain()
        assertEquals(ProviderDefinitionId("anthropic"), domain.id)
        assertEquals("Anthropic", domain.displayName)
        assertEquals(listOf<ProviderProtocol>(ProviderProtocol.Anthropic), domain.supportedProtocols)
        assertEquals(ProviderFieldId("api_key"), domain.fields.first().id)
        assertEquals(true, domain.fields.first().isSecret)

        val mappedBack = domain.toDto()
        assertEquals(dto, mappedBack)
    }

    @Test
    fun redactedProviderInstanceMappingWithHostValidation() {
        val dto = RedactedProviderInstanceDto(
            id = "inst-1",
            hostId = "host-primary",
            definitionId = "openai",
            displayName = "OpenAI Production",
            baseUrl = "https://api.openai.com/v1",
            credentialStatus = "configured",
            operationalStatus = "active",
            revision = "rev-1",
            configuredFieldIds = listOf("api_key"),
            configuredHeaderNames = listOf("X-Custom-Header"),
        )

        // Successful mapping with matching host
        val domain = dto.toDomain(expectedHostId = primaryHost)
        assertEquals(ProviderInstanceId("inst-1"), domain.id)
        assertEquals(primaryHost, domain.hostId)
        assertEquals(CredentialStatus.Configured, domain.credentialStatus)
        assertEquals(OperationalStatus.Active, domain.operationalStatus)
        assertEquals(ProviderRevision("rev-1"), domain.revision)
        assertEquals(listOf("X-Custom-Header"), domain.configuredHeaderNames)

        val mappedBack = domain.toDto()
        assertEquals(dto, mappedBack)

        // Throws HostMismatchException on mismatched host
        assertFailsWith<HostMismatchException> {
            dto.toDomain(expectedHostId = HostId("host-other"))
        }
    }

    @Test
    fun modelRouteMappingWithUnknownEnumsAndHostValidation() {
        val dto = ModelRouteDto(
            id = "route-claude-3-5-sonnet",
            hostId = "host-primary",
            providerInstanceId = "inst-anthropic-1",
            modelHandle = "claude-3-5-sonnet-20241022",
            displayName = "Claude 3.5 Sonnet",
            contextWindowLimit = 200000,
            availability = "future_tier_status",
            visibility = "future_visibility_mode",
            aliases = listOf("claude-3-5-sonnet"),
            revision = "cat-rev-1",
        )

        val domain = dto.toDomain(expectedHostId = primaryHost)
        assertEquals(ModelRouteId("route-claude-3-5-sonnet"), domain.id)
        assertEquals(primaryHost, domain.hostId)
        assertEquals(ProviderInstanceId("inst-anthropic-1"), domain.providerInstanceId)
        assertEquals(CatalogRevision("cat-rev-1"), domain.revision)

        // Unknown wire enum values map safely to Unknown(raw)
        assertIs<ModelAvailability.Unknown>(domain.availability)
        assertEquals("future_tier_status", (domain.availability as ModelAvailability.Unknown).raw)

        assertIs<VisibilityPolicy.Unknown>(domain.visibility)
        assertEquals("future_visibility_mode", (domain.visibility as VisibilityPolicy.Unknown).raw)

        val mappedBack = domain.toDto()
        assertEquals(dto, mappedBack)

        // Throws HostMismatchException on mismatched host
        assertFailsWith<HostMismatchException> {
            dto.toDomain(expectedHostId = HostId("host-different"))
        }
    }
}
