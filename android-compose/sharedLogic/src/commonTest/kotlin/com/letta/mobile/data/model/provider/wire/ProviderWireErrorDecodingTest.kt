package com.letta.mobile.data.model.provider.wire

import com.letta.mobile.data.model.HostId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProviderWireErrorDecodingTest {
    @Test
    fun stableErrorCodesRoundTripThroughSecretSafeCodec() {
        val codes = listOf(
            ProviderErrorCode.IdempotencyConflict,
            ProviderErrorCode.RevisionConflict,
            ProviderErrorCode.Unauthorized,
            ProviderErrorCode.CapabilityDenied,
            ProviderErrorCode.ProviderNotFound,
            ProviderErrorCode.ModelNotFound,
            ProviderErrorCode.ValidationFailed,
            ProviderErrorCode.DependencyViolation,
            ProviderErrorCode.HostMismatch,
            ProviderErrorCode.UnsupportedContractVersion,
        )

        codes.forEach { code ->
            val encoded = ProviderManagementWireCodec.encodeErrorForTest(ProviderManagementErrorDto(code))
            val decoded = ProviderManagementWireCodec.decodeErrorForTest(encoded)
            assertEquals(code, decoded.code)
        }
    }

    @Test
    fun unknownErrorsDecodeToNonReflectiveTypedValue() {
        val decoded = ProviderManagementWireCodec.decodeErrorForTest(
            """{"code":"FUTURE_ERROR","message":"server detail","extra":true}""",
        )

        assertEquals(ProviderErrorCode.Unknown, decoded.code)
        assertEquals("Unknown provider error", decoded.message)
        assertFalse(decoded.toString().contains("server detail"))
    }

    @Test
    fun capabilityIsFailClosedWhenMissingOrUnknown() {
        val missing = ProviderManagementWireCodec.decodeMutationResponse(
            """{"contract_version":1,"success":true}""",
            HostId("host-1"),
        )
        val unknown = ProviderManagementWireCodec.decodeMutationResponse(
            """{"contract_version":1,"success":true,"mutation_capability":"future-value"}""",
            HostId("host-1"),
        )
        val allowed = ProviderManagementWireCodec.decodeMutationResponse(
            """{"contract_version":1,"success":true,"mutation_capability":"allowed"}""",
            HostId("host-1"),
        )

        assertEquals(ProviderMutationCapability.Denied, missing.mutationCapability)
        assertFalse(missing.mutationCapability.isAllowed)
        assertEquals(ProviderMutationCapability.Unknown, unknown.mutationCapability)
        assertFalse(unknown.mutationCapability.isAllowed)
        assertEquals(ProviderMutationCapability.Allowed, allowed.mutationCapability)
    }
}
