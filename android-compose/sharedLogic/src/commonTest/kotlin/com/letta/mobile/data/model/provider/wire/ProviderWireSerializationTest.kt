package com.letta.mobile.data.model.provider.wire

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ProviderInstanceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderWireSerializationTest {
    private val host = HostId("host-primary")

    @Test
    fun definitionsRoundTripUsesStableSerialNames() {
        val response = ProviderDefinitionsListResponseDto(
            contractVersion = 1,
            definitions = listOf(
                ProviderDefinitionDto(
                    id = "openai",
                    displayName = "OpenAI",
                    supportedProtocols = listOf("openai"),
                    fields = listOf(ProviderFieldSchemaDto("api_key", "API Key", isSecret = true)),
                ),
            ),
        )

        val encoded = ProviderManagementWireCodec.encodeDefinitionsForTest(response)
        val decoded = ProviderManagementWireCodec.decodeDefinitions(encoded)

        assertTrue(encoded.contains("\"contract_version\":1"))
        assertTrue(encoded.contains("\"display_name\":\"OpenAI\""))
        assertTrue(encoded.contains("\"is_secret\":true"))
        assertEquals("openai", decoded.single().id.value)
    }

    @Test
    fun instancesAndRoutesRoundTripThroughProductionCodec() {
        val instances = ProviderInstancesListResponseDto(
            contractVersion = 1,
            hostId = host.value,
            instances = listOf(
                RedactedProviderInstanceDto(
                    id = "inst-1",
                    hostId = host.value,
                    definitionId = "openai",
                    displayName = "Primary OpenAI",
                    credentialStatus = "configured",
                ),
            ),
        )
        val routes = ModelRoutesListResponseDto(
            contractVersion = 1,
            hostId = host.value,
            routes = listOf(
                ModelRouteDto(
                    id = "route-1",
                    hostId = host.value,
                    providerInstanceId = "inst-1",
                    modelHandle = "gpt-4o",
                    displayName = "GPT-4o",
                ),
            ),
        )

        val decodedInstances = ProviderManagementWireCodec.decodeInstances(
            ProviderManagementWireCodec.encodeInstancesForTest(instances),
            host,
        )
        val decodedRoutes = ProviderManagementWireCodec.decodeRoutes(
            ProviderManagementWireCodec.encodeRoutesForTest(routes),
            host,
        )

        assertEquals(ProviderInstanceId("inst-1"), decodedInstances.single().id)
        assertEquals(ProviderInstanceId("inst-1"), decodedRoutes.single().providerInstanceId)
    }

    @Test
    fun additiveUnknownFieldsAreAcceptedAndMissingOptionalFieldsUseDefaults() {
        val payload = """{
            "contract_version":1,
            "host_id":"host-primary",
            "future_envelope_field":{"nested":true},
            "instances":[{
                "id":"inst-1",
                "host_id":"host-primary",
                "definition_id":"openai",
                "display_name":"OpenAI",
                "future_instance_field":42
            }]
        }"""

        val decoded = ProviderManagementWireCodec.decodeInstances(payload, host).single()

        assertEquals("OpenAI", decoded.displayName)
        assertEquals("not_required", decoded.credentialStatus.wireValue)
    }

    @Test
    fun missingMalformedAndUnsupportedVersionsFailWithTypedSafeReasons() {
        val missing = assertFailsWith<ProviderWireContractException> {
            ProviderManagementWireCodec.decodeDefinitions("""{"definitions":[]}""")
        }
        val malformed = assertFailsWith<ProviderWireContractException> {
            ProviderManagementWireCodec.decodeDefinitions("""{"contract_version":"one","definitions":[]}""")
        }
        val unsupported = assertFailsWith<ProviderWireContractException> {
            ProviderManagementWireCodec.decodeDefinitions("""{"contract_version":2,"definitions":[]}""")
        }

        assertEquals(ProviderWireContractException.Reason.MalformedPayload, missing.reason)
        assertEquals(ProviderWireContractException.Reason.MalformedPayload, malformed.reason)
        assertEquals(ProviderWireContractException.Reason.UnsupportedVersion, unsupported.reason)
    }

    @Test
    fun everyMutationCommandEmitsContractVersion() {
        val bodies = listOf(
            ProviderManagementWireCodec.encode(
                ClearProviderCredentialCommandDto(hostId = host.value, instanceId = "inst-1"),
            ),
            ProviderManagementWireCodec.encode(
                SetProviderEnabledCommandDto(hostId = host.value, instanceId = "inst-1", enabled = true),
            ),
            ProviderManagementWireCodec.encode(
                DeleteProviderInstanceCommandDto(hostId = host.value, instanceId = "inst-1"),
            ),
            ProviderManagementWireCodec.encode(
                SetModelVisibilityCommandDto(
                    hostId = host.value,
                    routeId = "route-1",
                    visibility = ModelVisibilityWireValue.Visible,
                ),
            ),
        )

        bodies.forEach { assertTrue(it.contains("\"contract_version\":1"), it) }

        assertFailsWith<ProviderWireEncodingException> {
            ProviderManagementWireCodec.encode(
                DeleteProviderInstanceCommandDto(
                    contractVersion = 2,
                    hostId = host.value,
                    instanceId = "inst-1",
                ),
            )
        }
    }
}
