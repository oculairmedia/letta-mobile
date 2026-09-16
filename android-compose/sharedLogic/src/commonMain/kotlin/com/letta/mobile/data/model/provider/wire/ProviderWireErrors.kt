package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Stable error codes. Unknown values decode without retaining hostile text. */
@Serializable(with = ProviderErrorCodeSerializer::class)
sealed interface ProviderErrorCode {
    val wireValue: String

    data object IdempotencyConflict : ProviderErrorCode { override val wireValue = "IDEMPOTENCY_CONFLICT" }
    data object RevisionConflict : ProviderErrorCode { override val wireValue = "REVISION_CONFLICT" }
    data object Unauthorized : ProviderErrorCode { override val wireValue = "UNAUTHORIZED" }
    data object CapabilityDenied : ProviderErrorCode { override val wireValue = "CAPABILITY_DENIED" }
    data object ProviderNotFound : ProviderErrorCode { override val wireValue = "PROVIDER_NOT_FOUND" }
    data object ModelNotFound : ProviderErrorCode { override val wireValue = "MODEL_NOT_FOUND" }
    data object ValidationFailed : ProviderErrorCode { override val wireValue = "VALIDATION_FAILED" }
    data object DependencyViolation : ProviderErrorCode { override val wireValue = "DEPENDENCY_VIOLATION" }
    data object HostMismatch : ProviderErrorCode { override val wireValue = "HOST_MISMATCH" }
    data object UnsupportedContractVersion : ProviderErrorCode { override val wireValue = "UNSUPPORTED_CONTRACT_VERSION" }
    data object Unknown : ProviderErrorCode { override val wireValue = "UNKNOWN" }

    companion object {
        fun fromWire(raw: String): ProviderErrorCode = when (raw.trim().uppercase()) {
            "IDEMPOTENCY_CONFLICT" -> IdempotencyConflict
            "REVISION_CONFLICT" -> RevisionConflict
            "UNAUTHORIZED" -> Unauthorized
            "CAPABILITY_DENIED" -> CapabilityDenied
            "PROVIDER_NOT_FOUND" -> ProviderNotFound
            "MODEL_NOT_FOUND" -> ModelNotFound
            "VALIDATION_FAILED" -> ValidationFailed
            "DEPENDENCY_VIOLATION" -> DependencyViolation
            "HOST_MISMATCH" -> HostMismatch
            "UNSUPPORTED_CONTRACT_VERSION" -> UnsupportedContractVersion
            else -> Unknown
        }
    }
}

object ProviderErrorCodeSerializer : KSerializer<ProviderErrorCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProviderErrorCode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ProviderErrorCode) =
        encoder.encodeString(value.wireValue)

    override fun deserialize(decoder: Decoder): ProviderErrorCode =
        ProviderErrorCode.fromWire(decoder.decodeString())
}

/** Missing and unknown capability values are denied by construction. */
@Serializable(with = ProviderMutationCapabilitySerializer::class)
sealed interface ProviderMutationCapability {
    val wireValue: String
    val isAllowed: Boolean

    data object Allowed : ProviderMutationCapability {
        override val wireValue = "allowed"
        override val isAllowed = true
    }

    data object Denied : ProviderMutationCapability {
        override val wireValue = "denied"
        override val isAllowed = false
    }

    data object Unknown : ProviderMutationCapability {
        override val wireValue = "unknown"
        override val isAllowed = false
    }

    companion object {
        fun fromWire(raw: String): ProviderMutationCapability = when (raw.trim().lowercase()) {
            "allowed" -> Allowed
            "denied" -> Denied
            else -> Unknown
        }
    }
}

object ProviderMutationCapabilitySerializer : KSerializer<ProviderMutationCapability> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProviderMutationCapability", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ProviderMutationCapability) =
        encoder.encodeString(value.wireValue)

    override fun deserialize(decoder: Decoder): ProviderMutationCapability =
        ProviderMutationCapability.fromWire(decoder.decodeString())
}

/**
 * Secret-safe typed error. Wire-controlled message/identifier/revision text is consumed by the
 * serializer but never retained, so it cannot enter value semantics, snapshots, or diagnostics.
 */
@Serializable(with = ProviderManagementErrorDtoSerializer::class)
class ProviderManagementErrorDto(val code: ProviderErrorCode) {
    val message: String get() = code.safeMessage()

    override fun toString(): String =
        "ProviderManagementErrorDto(code=${code.wireValue}, message=<redacted>)"
}

object ProviderManagementErrorDtoSerializer : KSerializer<ProviderManagementErrorDto> {
    override val descriptor = buildClassSerialDescriptor("ProviderManagementErrorDto") {
        element<ProviderErrorCode>("code")
        element<String>("message", isOptional = true)
        element<String>("target_id", isOptional = true)
        element<String>("expected_revision", isOptional = true)
        element<String>("current_revision", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ProviderManagementErrorDto) {
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(descriptor, 0, ProviderErrorCodeSerializer, value.code)
        output.encodeStringElement(descriptor, 1, value.message)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ProviderManagementErrorDto {
        val input = decoder.beginStructure(descriptor)
        var code: ProviderErrorCode? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> code = input.decodeSerializableElement(descriptor, index, ProviderErrorCodeSerializer)
                1, 2, 3, 4 -> input.decodeStringElement(descriptor, index)
                else -> throw SerializationException("Malformed provider-management error")
            }
        }
        input.endStructure(descriptor)
        return ProviderManagementErrorDto(
            code ?: throw SerializationException("Malformed provider-management error"),
        )
    }
}

internal fun ProviderManagementErrorDto.redacted(): ProviderManagementErrorDto =
    ProviderManagementErrorDto(code)

private fun ProviderErrorCode.safeMessage(): String = when (this) {
    ProviderErrorCode.IdempotencyConflict -> "Idempotency conflict"
    ProviderErrorCode.RevisionConflict -> "Revision conflict"
    ProviderErrorCode.Unauthorized -> "Unauthorized"
    ProviderErrorCode.CapabilityDenied -> "Provider mutation capability denied"
    ProviderErrorCode.ProviderNotFound -> "Provider not found"
    ProviderErrorCode.ModelNotFound -> "Model not found"
    ProviderErrorCode.ValidationFailed -> "Provider request validation failed"
    ProviderErrorCode.DependencyViolation -> "Provider dependency violation"
    ProviderErrorCode.HostMismatch -> "Provider payload host mismatch"
    ProviderErrorCode.UnsupportedContractVersion -> "Unsupported provider contract version"
    ProviderErrorCode.Unknown -> "Unknown provider error"
}
