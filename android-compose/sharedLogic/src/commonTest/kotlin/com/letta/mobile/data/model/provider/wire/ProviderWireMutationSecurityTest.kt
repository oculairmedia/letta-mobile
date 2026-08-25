package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderWireMutationSecurityTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val secretSentinel = "sk-live-sentinel-xyz-987654321-DO-NOT-LEAK"

    @Test
    fun createCommandMasksSecretInToString() {
        val cmd = CreateProviderInstanceCommandDto(
            hostId = "host-1",
            definitionId = "openai",
            displayName = "OpenAI Production",
            initialApiKey = secretSentinel,
        )

        val str = cmd.toString()
        assertFalse(str.contains(secretSentinel))
        assertTrue(str.contains("<secret withheld>"))
    }

    @Test
    fun replaceCredentialCommandMasksSecretInToString() {
        val cmd = ReplaceProviderCredentialCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            expectedRevision = "rev-1",
            apiKey = secretSentinel,
        )

        val str = cmd.toString()
        assertFalse(str.contains(secretSentinel))
        assertTrue(str.contains("<secret withheld>"))
    }

    @Test
    fun updateCommandHasNoCredentialField() {
        val cmd = UpdateProviderInstanceCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            expectedRevision = "rev-1",
            displayName = "Updated Display Name",
            baseUrl = "https://custom.endpoint.com",
            customHeaders = mapOf("X-Key" to "val"),
        )

        val encoded = json.encodeToString(cmd)
        assertFalse(encoded.contains("apiKey", ignoreCase = true))
        assertFalse(encoded.contains("secret", ignoreCase = true))
        assertFalse(encoded.contains("credential", ignoreCase = true))
    }

    @Test
    fun replaceAndClearCommandsAreStructurallyDistinct() {
        val replaceCmd = ReplaceProviderCredentialCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            expectedRevision = "rev-1",
            apiKey = secretSentinel,
        )

        val clearCmd = ClearProviderCredentialCommandDto(
            hostId = "host-1",
            instanceId = "inst-1",
            expectedRevision = "rev-1",
        )

        val encodedReplace = json.encodeToString(replaceCmd)
        val encodedClear = json.encodeToString(clearCmd)

        assertTrue(encodedReplace.contains("\"api_key\""))
        assertFalse(encodedClear.contains("\"api_key\""))
    }

    @Test
    fun mutationResponseContainsOnlyRedactedStateAndNoSecrets() {
        val response = ProviderMutationResponseDto(
            contractVersion = 1,
            success = true,
            revision = "rev-2",
            instance = RedactedProviderInstanceDto(
                id = "inst-1",
                hostId = "host-1",
                definitionId = "openai",
                displayName = "OpenAI Prod",
                credentialStatus = "configured",
                operationalStatus = "active",
                revision = "rev-2",
                configuredFieldIds = listOf("api_key"),
            ),
        )

        val encoded = json.encodeToString(response)
        val str = response.toString()

        assertFalse(encoded.contains(secretSentinel))
        assertFalse(str.contains(secretSentinel))
        assertFalse(encoded.contains("apiKey", ignoreCase = true))
        assertFalse(encoded.contains("secretValue", ignoreCase = true))
        assertTrue(encoded.contains("\"configured\""))
    }
}
