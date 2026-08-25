package com.letta.mobile.data.model.provider.wire

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CredentialStatus
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.ProviderProtocol
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProviderWireMapperTest {
    private val host = HostId("host-primary")

    @Test
    fun providerDefinitionUsesTypedIdentifiersAndCanonicalProtocols() {
        val dto = ProviderDefinitionDto(
            id = "anthropic",
            displayName = "Anthropic",
            supportedProtocols = listOf(" ANTHROPIC ", "future-protocol"),
            fields = listOf(ProviderFieldSchemaDto("api_key", "API Key", isSecret = true)),
        )

        val domain = dto.toDomain()

        assertEquals(ProviderDefinitionId("anthropic"), domain.id)
        assertEquals(ProviderProtocol.Anthropic, domain.supportedProtocols.first())
        assertEquals("unknown", assertIs<ProviderProtocol.Unknown>(domain.supportedProtocols.last()).raw)
        assertEquals(dto.id, domain.toDto().id)
    }

    @Test
    fun hostScopedRecordsRequireActiveHost() {
        val instance = redactedInstance(hostId = host.value)
        val route = modelRoute(hostId = host.value)

        assertEquals(ProviderInstanceId("inst-1"), instance.toDomain(host).id)
        assertEquals(ProviderInstanceId("inst-1"), route.toDomain(host).providerInstanceId)
        assertFailsWith<HostMismatchException> { instance.toDomain(HostId("other")) }
        assertFailsWith<HostMismatchException> { route.toDomain(HostId("other")) }
    }

    @Test
    fun listHostValidationIsAtomicAcrossEnvelopeAndChildren() {
        val mismatchedChild = ProviderInstancesListResponseDto(
            contractVersion = 1,
            hostId = host.value,
            instances = listOf(redactedInstance(host.value), redactedInstance("other", "inst-2")),
        )
        val mismatchedEnvelope = ModelRoutesListResponseDto(
            contractVersion = 1,
            hostId = "other",
            routes = listOf(modelRoute(host.value)),
        )

        assertFailsWith<HostMismatchException> { mismatchedChild.toDomain(host) }
        assertFailsWith<HostMismatchException> { mismatchedEnvelope.toDomain(host) }
    }

    @Test
    fun unknownStatusesDecodeWithoutRetainingUntrustedRawText() {
        val sentinel = "sk-secret-unknown-enum"
        val instance = redactedInstance(host.value).copy(
            credentialStatus = sentinel,
            operationalStatus = sentinel,
        ).toDomain(host)
        val route = modelRoute(host.value).copy(
            availability = sentinel,
            visibility = sentinel,
        ).toDomain(host)

        assertEquals("unknown", assertIs<CredentialStatus.Unknown>(instance.credentialStatus).raw)
        assertEquals("unknown", assertIs<ModelAvailability.Unknown>(route.availability).raw)
        assertEquals("unknown", assertIs<VisibilityPolicy.Unknown>(route.visibility).raw)
        assertEquals(ModelVisibilityWireValue.Unknown, ModelVisibilityWireValue.fromWire(sentinel))
    }

    private fun redactedInstance(hostId: String, id: String = "inst-1") = RedactedProviderInstanceDto(
        id = id,
        hostId = hostId,
        definitionId = "openai",
        displayName = "OpenAI",
        credentialStatus = "configured",
    )

    private fun modelRoute(hostId: String) = ModelRouteDto(
        id = "route-1",
        hostId = hostId,
        providerInstanceId = "inst-1",
        modelHandle = "gpt-4o",
        displayName = "GPT-4o",
    )
}
