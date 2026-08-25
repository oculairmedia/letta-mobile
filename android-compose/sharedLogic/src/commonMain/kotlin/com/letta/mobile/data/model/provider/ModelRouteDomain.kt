package com.letta.mobile.data.model.provider

import com.letta.mobile.data.model.CatalogRevision
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ImmutableListSerializer
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderInstanceId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Operational availability status for an individual model route.
 *
 * Decoding uses a one-way canonicalization policy: wire variations (e.g. whitespace,
 * casing) parse into canonical singletons whose [wireValue] re-encodes to the canonical
 * string. Unknown availability statuses are preserved in [Unknown].
 */
@Serializable(with = ModelAvailabilitySerializer::class)
sealed interface ModelAvailability {
    val wireValue: String

    @Serializable(with = ModelAvailabilitySerializer::class)
    data object Available : ModelAvailability {
        override val wireValue: String = "available"
    }

    @Serializable(with = ModelAvailabilitySerializer::class)
    data object Deprecated : ModelAvailability {
        override val wireValue: String = "deprecated"
    }

    @Serializable(with = ModelAvailabilitySerializer::class)
    data object Disabled : ModelAvailability {
        override val wireValue: String = "disabled"
    }

    @Serializable(with = ModelAvailabilitySerializer::class)
    data object QuotaExceeded : ModelAvailability {
        override val wireValue: String = "quota_exceeded"
    }

    @Serializable(with = ModelAvailabilitySerializer::class)
    data class Unknown(val raw: String) : ModelAvailability {
        override val wireValue: String get() = raw
    }

    companion object {
        fun fromWire(raw: String): ModelAvailability = when (raw.trim().lowercase()) {
            "available" -> Available
            "deprecated" -> Deprecated
            "disabled" -> Disabled
            "quota_exceeded" -> QuotaExceeded
            else -> Unknown(raw)
        }
    }
}

object ModelAvailabilitySerializer : KSerializer<ModelAvailability> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ModelAvailability", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ModelAvailability) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): ModelAvailability {
        return ModelAvailability.fromWire(decoder.decodeString())
    }
}

/**
 * Visibility policy for a model route within user-facing picker projections.
 *
 * Decoding uses a one-way canonicalization policy: wire variations (e.g. whitespace,
 * casing, aliases like "automatic") parse into canonical singletons whose [wireValue]
 * re-encodes to the canonical string (e.g. "auto"). Unknown policies are preserved in [Unknown].
 */
@Serializable(with = VisibilityPolicySerializer::class)
sealed interface VisibilityPolicy {
    val wireValue: String

    @Serializable(with = VisibilityPolicySerializer::class)
    data object Visible : VisibilityPolicy {
        override val wireValue: String = "visible"
    }

    @Serializable(with = VisibilityPolicySerializer::class)
    data object Hidden : VisibilityPolicy {
        override val wireValue: String = "hidden"
    }

    @Serializable(with = VisibilityPolicySerializer::class)
    data object Automatic : VisibilityPolicy {
        override val wireValue: String = "auto"
    }

    @Serializable(with = VisibilityPolicySerializer::class)
    data class Unknown(val raw: String) : VisibilityPolicy {
        override val wireValue: String get() = raw
    }

    companion object {
        fun fromWire(raw: String): VisibilityPolicy = when (raw.trim().lowercase()) {
            "visible" -> Visible
            "hidden" -> Hidden
            "auto", "automatic" -> Automatic
            else -> Unknown(raw)
        }
    }
}

object VisibilityPolicySerializer : KSerializer<VisibilityPolicy> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("VisibilityPolicy", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: VisibilityPolicy) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): VisibilityPolicy {
        return VisibilityPolicy.fromWire(decoder.decodeString())
    }
}

/**
 * Immutable canonical model route representing an addressable model configuration
 * on a specific provider instance.
 */
@Serializable
data class CanonicalModelRoute(
    val id: ModelRouteId,
    @SerialName("host_id") val hostId: HostId,
    @SerialName("provider_instance_id") val providerInstanceId: ProviderInstanceId,
    @SerialName("model_handle") val modelHandle: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    val availability: ModelAvailability = ModelAvailability.Available,
    val visibility: VisibilityPolicy = VisibilityPolicy.Automatic,
    @Serializable(with = ImmutableListSerializer::class)
    val aliases: ImmutableList<String> = persistentListOf(),
    val revision: CatalogRevision? = null,
)
