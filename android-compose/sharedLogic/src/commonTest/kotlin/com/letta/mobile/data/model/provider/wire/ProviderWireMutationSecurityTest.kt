package com.letta.mobile.data.model.provider.wire

import com.letta.mobile.data.model.HostId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProviderWireMutationSecurityTest {
    private val secret = "sk-live-sentinel-xyz-987654321-DO-NOT-LEAK"

    @Test
    fun secretExistsOnlyInOutboundCreateBody() {
        val command = CreateProviderInstanceCommandDto(
            hostId = "host-1",
            definitionId = "openai",
            displayName = "OpenAI Production",
            initialApiKey = SecretWriteValue(secret),
            customHeaders = SecretHeadersWriteValue(mapOf("Authorization" to secret)),
        )
        val sameInputs = CreateProviderInstanceCommandDto(
            hostId = "host-1",
            definitionId = "openai",
            displayName = "OpenAI Production",
            initialApiKey = SecretWriteValue(secret),
        )

        val outboundBody = ProviderManagementWireCodec.encode(command)

        assertTrue(outboundBody.contains(secret))
        assertEquals(2, outboundBody.windowed(secret.length).count { it == secret })
        assertNotEquals(command, sameInputs)
        assertSecretAbsent(command.toString(), sameInputs.toString(), command.hashCode().toString())
        assertTrue(command.toString().contains("withheld"))
    }

    @Test
    fun replacementHasIdentitySemanticsAndClearIsStructurallyDistinct() {
        val replace = ReplaceProviderCredentialCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            expectedRevision = "rev-1",
            apiKey = SecretWriteValue(secret),
        )
        val equivalentInput = ReplaceProviderCredentialCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            expectedRevision = "rev-1",
            apiKey = SecretWriteValue(secret),
        )
        val clear = ClearProviderCredentialCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            expectedRevision = "rev-1",
        )

        val replaceBody = ProviderManagementWireCodec.encode(replace)
        val clearBody = ProviderManagementWireCodec.encode(clear)

        assertTrue(replaceBody.contains(secret))
        assertFalse(clearBody.contains("api_key"))
        assertFalse(clearBody.contains(secret))
        assertNotEquals(replace, equivalentInput)
        assertSecretAbsent(replace.toString(), equivalentInput.toString(), replace.hashCode().toString())
    }

    @Test
    fun ordinaryUpdateCannotCarryCredentialOrHeaderValues() {
        val update = UpdateProviderInstanceCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            displayName = "Updated",
            baseUrl = "https://example.invalid/v1",
        )

        val body = ProviderManagementWireCodec.encode(update)

        assertSecretAbsent(body, update.toString())
        assertFalse(body.contains("api_key"))
        assertFalse(body.contains("custom_headers"))
    }

    @Test
    fun echoedSecretIsDiscardedFromTypedErrorsAndDiagnostics() {
        val payload = """{
            "contract_version":1,
            "success":false,
            "error":{
                "code":"FUTURE_${secret}",
                "message":"${secret}\ncontrol",
                "target_id":"${secret}",
                "expected_revision":"${secret}",
                "current_revision":"${secret}"
            }
        }"""

        val response = ProviderManagementWireCodec.decodeMutationResponse(payload, HostId("host-1"))
        val error = requireNotNull(response.error)
        val snapshot = ProviderManagementWireCodec.encodeErrorForTest(error)

        assertEquals(ProviderErrorCode.Unknown, error.code)
        assertSecretAbsent(response.toString(), error.toString(), snapshot)
        assertFalse(error.message.contains("control"))
        assertFalse(snapshot.contains("target_id"))
    }

    @Test
    fun credentialBearingBaseUrlsAreRejectedWithoutEchoingValues() {
        val createFailure = assertFailsWith<IllegalArgumentException> {
            CreateProviderInstanceCommandDto(
                hostId = "host-1",
                definitionId = "openai",
                displayName = "OpenAI",
                baseUrl = "https://user:$secret@example.invalid/v1",
            )
        }
        val updateFailure = assertFailsWith<IllegalArgumentException> {
            UpdateProviderInstanceCommandDto(
                hostId = "host-1",
                instanceId = "inst-1",
                baseUrl = "https://example.invalid/v1?token=$secret",
            )
        }

        assertSecretAbsent(createFailure.toString(), updateFailure.toString())
    }

    @Test
    fun malformedAndEncodingFailuresDoNotEchoSecrets() {
        val malformed = assertFailsWith<ProviderWireContractException> {
            ProviderManagementWireCodec.decodeMutationResponse(secret, HostId("host-1"))
        }
        val unknownVisibility = SetModelVisibilityCommandDto(
            hostId = "host-1",
            routeId = "route-1",
            visibility = ModelVisibilityWireValue.Unknown,
        )
        val encoding = assertFailsWith<ProviderWireEncodingException> {
            ProviderManagementWireCodec.encode(unknownVisibility)
        }

        assertSecretAbsent(malformed.toString(), malformed.message.orEmpty(), encoding.toString())
    }

    private fun assertSecretAbsent(vararg surfaces: String) {
        surfaces.forEach { surface -> assertFalse(surface.contains(secret), surface) }
    }
}
