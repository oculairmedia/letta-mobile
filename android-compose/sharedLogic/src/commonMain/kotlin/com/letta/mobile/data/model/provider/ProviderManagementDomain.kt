package com.letta.mobile.data.model.provider

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ImmutableListSerializer
import com.letta.mobile.data.model.ImmutableMapSerializer
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderFieldId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.ProviderRevision
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Immutable schema definition for a configurable provider field.
 *
 * [isSecret] indicates that the field accepts confidential input (e.g. an API key)
 * during provider configuration, but runtime domain records NEVER store plaintext
 * secrets.
 */
@Serializable
data class ProviderFieldSchema(
    val id: ProviderFieldId,
    val label: String,
    val description: String? = null,
    @SerialName("is_secret") val isSecret: Boolean = false,
    @SerialName("is_required") val isRequired: Boolean = false,
    val placeholder: String? = null,
)

/**
 * Immutable metadata and schema definition for an LLM provider type supported by a host.
 */
@Serializable
data class ProviderDefinition(
    val id: ProviderDefinitionId,
    @SerialName("display_name") val displayName: String,
    val description: String? = null,
    @Serializable(with = ImmutableListSerializer::class)
    @SerialName("supported_protocols") val supportedProtocols: ImmutableList<String> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class)
    val fields: ImmutableList<ProviderFieldSchema> = persistentListOf(),
    @SerialName("default_base_url") val defaultBaseUrl: String? = null,
)

/**
 * Credential configuration state for a provider instance.
 */
@Serializable(with = CredentialStatusSerializer::class)
sealed interface CredentialStatus {
    val wireValue: String

    @Serializable(with = CredentialStatusSerializer::class)
    data object Configured : CredentialStatus {
        override val wireValue: String = "configured"
    }

    @Serializable(with = CredentialStatusSerializer::class)
    data object Missing : CredentialStatus {
        override val wireValue: String = "missing"
    }

    @Serializable(with = CredentialStatusSerializer::class)
    data object Invalid : CredentialStatus {
        override val wireValue: String = "invalid"
    }

    @Serializable(with = CredentialStatusSerializer::class)
    data object NotRequired : CredentialStatus {
        override val wireValue: String = "not_required"
    }

    @Serializable(with = CredentialStatusSerializer::class)
    data class Unknown(val raw: String) : CredentialStatus {
        override val wireValue: String get() = raw
    }

    companion object {
        fun fromWire(raw: String): CredentialStatus = when (raw.trim().lowercase()) {
            "configured" -> Configured
            "missing" -> Missing
            "invalid" -> Invalid
            "not_required" -> NotRequired
            else -> Unknown(raw)
        }
    }
}

object CredentialStatusSerializer : KSerializer<CredentialStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CredentialStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CredentialStatus) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): CredentialStatus {
        return CredentialStatus.fromWire(decoder.decodeString())
    }
}

/**
 * Operational / health status for a provider instance.
 */
@Serializable(with = OperationalStatusSerializer::class)
sealed interface OperationalStatus {
    val wireValue: String

    @Serializable(with = OperationalStatusSerializer::class)
    data object Active : OperationalStatus {
        override val wireValue: String = "active"
    }

    @Serializable(with = OperationalStatusSerializer::class)
    data object Degraded : OperationalStatus {
        override val wireValue: String = "degraded"
    }

    @Serializable(with = OperationalStatusSerializer::class)
    data object Disabled : OperationalStatus {
        override val wireValue: String = "disabled"
    }

    @Serializable(with = OperationalStatusSerializer::class)
    data object Unavailable : OperationalStatus {
        override val wireValue: String = "unavailable"
    }

    @Serializable(with = OperationalStatusSerializer::class)
    data class Unknown(val raw: String) : OperationalStatus {
        override val wireValue: String get() = raw
    }

    companion object {
        fun fromWire(raw: String): OperationalStatus = when (raw.trim().lowercase()) {
            "active" -> Active
            "degraded" -> Degraded
            "disabled" -> Disabled
            "unavailable" -> Unavailable
            else -> Unknown(raw)
        }
    }
}

object OperationalStatusSerializer : KSerializer<OperationalStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OperationalStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OperationalStatus) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): OperationalStatus {
        return OperationalStatus.fromWire(decoder.decodeString())
    }
}

/**
 * Immutable domain model for a configured provider instance on a host.
 *
 * This record is strictly secret-free: it contains ONLY public metadata, status indicators,
 * and the IDs of configured fields (indicating presence without exposing credentials).
 */
@Serializable
data class RedactedProviderInstance(
    val id: ProviderInstanceId,
    @SerialName("host_id") val hostId: HostId,
    @SerialName("definition_id") val definitionId: ProviderDefinitionId,
    @SerialName("display_name") val displayName: String,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("credential_status") val credentialStatus: CredentialStatus = CredentialStatus.NotRequired,
    @SerialName("operational_status") val operationalStatus: OperationalStatus = OperationalStatus.Active,
    val revision: ProviderRevision? = null,
    @Serializable(with = ImmutableListSerializer::class)
    @SerialName("configured_field_ids") val configuredFieldIds: ImmutableList<ProviderFieldId> = persistentListOf(),
    @Serializable(with = ImmutableMapSerializer::class)
    @SerialName("custom_headers") val customHeaders: ImmutableMap<String, String> = persistentMapOf(),
)
