package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Standard error codes for provider management RPC mutations and queries.
 */
@Serializable(with = ProviderErrorCodeSerializer::class)
sealed interface ProviderErrorCode {
    val wireValue: String

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object IdempotencyConflict : ProviderErrorCode {
        override val wireValue: String = "IDEMPOTENCY_CONFLICT"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object RevisionConflict : ProviderErrorCode {
        override val wireValue: String = "REVISION_CONFLICT"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object Unauthorized : ProviderErrorCode {
        override val wireValue: String = "UNAUTHORIZED"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object ProviderNotFound : ProviderErrorCode {
        override val wireValue: String = "PROVIDER_NOT_FOUND"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object ModelNotFound : ProviderErrorCode {
        override val wireValue: String = "MODEL_NOT_FOUND"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object ValidationFailed : ProviderErrorCode {
        override val wireValue: String = "VALIDATION_FAILED"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object DependencyViolation : ProviderErrorCode {
        override val wireValue: String = "DEPENDENCY_VIOLATION"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data object HostMismatch : ProviderErrorCode {
        override val wireValue: String = "HOST_MISMATCH"
    }

    @Serializable(with = ProviderErrorCodeSerializer::class)
    data class Unknown(val raw: String) : ProviderErrorCode {
        override val wireValue: String get() = raw
    }

    companion object {
        fun fromWire(raw: String): ProviderErrorCode = when (raw.trim().uppercase()) {
            "IDEMPOTENCY_CONFLICT" -> IdempotencyConflict
            "REVISION_CONFLICT" -> RevisionConflict
            "UNAUTHORIZED" -> Unauthorized
            "PROVIDER_NOT_FOUND" -> ProviderNotFound
            "MODEL_NOT_FOUND" -> ModelNotFound
            "VALIDATION_FAILED" -> ValidationFailed
            "DEPENDENCY_VIOLATION" -> DependencyViolation
            "HOST_MISMATCH" -> HostMismatch
            else -> Unknown(raw)
        }
    }
}

object ProviderErrorCodeSerializer : KSerializer<ProviderErrorCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProviderErrorCode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ProviderErrorCode) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): ProviderErrorCode {
        return ProviderErrorCode.fromWire(decoder.decodeString())
    }
}

/**
 * Structured error details returned by failed provider operations.
 */
@Serializable
data class ProviderManagementErrorDto(
    @SerialName("code") val code: ProviderErrorCode,
    @SerialName("message") val message: String,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("current_revision") val currentRevision: String? = null,
)
