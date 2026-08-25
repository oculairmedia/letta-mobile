package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProviderWireErrorDecodingTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    @Test
    fun knownErrorCodesSerializeAndDeserialize() {
        val codes = listOf(
            ProviderErrorCode.IdempotencyConflict to "\"IDEMPOTENCY_CONFLICT\"",
            ProviderErrorCode.RevisionConflict to "\"REVISION_CONFLICT\"",
            ProviderErrorCode.Unauthorized to "\"UNAUTHORIZED\"",
            ProviderErrorCode.ProviderNotFound to "\"PROVIDER_NOT_FOUND\"",
            ProviderErrorCode.ModelNotFound to "\"MODEL_NOT_FOUND\"",
            ProviderErrorCode.ValidationFailed to "\"VALIDATION_FAILED\"",
            ProviderErrorCode.DependencyViolation to "\"DEPENDENCY_VIOLATION\"",
            ProviderErrorCode.HostMismatch to "\"HOST_MISMATCH\"",
        )

        for ((code, expectedJson) in codes) {
            assertEquals(expectedJson, json.encodeToString<ProviderErrorCode>(code))
            assertEquals(code, json.decodeFromString<ProviderErrorCode>(expectedJson))
        }
    }

    @Test
    fun unknownErrorCodeDecodesWithoutThrowing() {
        val decoded = json.decodeFromString<ProviderErrorCode>("\"RATE_LIMIT_EXCEEDED\"")
        assertIs<ProviderErrorCode.Unknown>(decoded)
        assertEquals("RATE_LIMIT_EXCEEDED", decoded.raw)
        assertEquals("RATE_LIMIT_EXCEEDED", decoded.wireValue)
        assertEquals("\"RATE_LIMIT_EXCEEDED\"", json.encodeToString<ProviderErrorCode>(decoded))
    }

    @Test
    fun errorDtoRoundTripAndSecretSafety() {
        val errorDto = ProviderManagementErrorDto(
            code = ProviderErrorCode.RevisionConflict,
            message = "Stale revision: expected rev-1, found rev-2",
            targetId = "inst-1",
            expectedRevision = "rev-1",
            currentRevision = "rev-2",
        )

        val encoded = json.encodeToString(errorDto)
        val decoded = json.decodeFromString<ProviderManagementErrorDto>(encoded)

        assertEquals(errorDto, decoded)
        assertFalse(encoded.contains("secret", ignoreCase = true))
        assertFalse(encoded.contains("key", ignoreCase = true))
    }
}
