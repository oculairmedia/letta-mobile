package com.letta.mobile.data.servicecredentials

import com.letta.mobile.data.rpcwire.SecretWriteValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a write does to the stored secret. */
sealed interface ServiceSecretChange {
    /** Keep the stored secret, but only while the address's origin is unchanged. */
    data object Keep : ServiceSecretChange

    /** Delete the stored secret. */
    data object Remove : ServiceSecretChange

    /** Store [secret] in place of any stored one. Printing it shows no part of the secret. */
    class Replace(
        /** The new secret; read only to encode the write. */
        val secret: SecretWriteValue,
    ) : ServiceSecretChange {
        override fun toString(): String = "Replace($secret)"
    }
}

/**
 * Write-only command that stores one service's settings on the host. It is not a data class and its
 * serializer cannot decode, so the secret it may carry has no equality, copy or read-back path.
 */
@Serializable(with = ServiceCredentialSetCommandSerializer::class)
class ServiceCredentialSetCommand(
    /** The service. */
    val serviceId: ServiceId,
    /** The address; null uses the service's default. */
    val baseUrl: String? = null,
    /** What to do with the stored secret. */
    val secret: ServiceSecretChange = ServiceSecretChange.Keep,
    /** The service's settings, replacing the stored ones. */
    val config: Map<String, String> = emptyMap(),
    /** The revision this write replaces; null writes regardless of concurrent changes. */
    val expectedRevision: String? = null,
) {
    override fun toString(): String =
        "ServiceCredentialSetCommand(serviceId=$serviceId, secret=$secret, configKeys=${config.keys}, expectedRevision=$expectedRevision)"
}

/** `service.credential.test` params. */
@Serializable
data class ServiceCredentialTestParams(
    /** The service whose stored credential to test. */
    @SerialName("service_id") val serviceId: ServiceId,
)

/** `service.credential.remove` params. */
@Serializable
data class ServiceCredentialRemoveParams(
    /** The service to remove. */
    @SerialName("service_id") val serviceId: ServiceId,
    /** The revision this removal replaces; null removes regardless. */
    @SerialName("expected_revision") val expectedRevision: String? = null,
)

/** Encodes [ServiceCredentialSetCommand] as `service.credential.set` params; refuses to decode one. */
object ServiceCredentialSetCommandSerializer : KSerializer<ServiceCredentialSetCommand> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ServiceCredentialSetCommand")

    override fun serialize(encoder: Encoder, value: ServiceCredentialSetCommand) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Service credential writes are JSON only")
        json.encodeJsonElement(
            buildJsonObject {
                put("contract_version", ServiceCredentialWire.CONTRACT_VERSION)
                put("service_id", value.serviceId.value)
                value.baseUrl?.let { put("base_url", it) }
                when (val secret = value.secret) {
                    ServiceSecretChange.Keep -> Unit
                    ServiceSecretChange.Remove -> put("remove_secret", true)
                    is ServiceSecretChange.Replace -> put("secret", secret.secret.revealForEncoding())
                }
                if (value.config.isNotEmpty()) {
                    put("config", buildJsonObject { value.config.forEach { (key, setting) -> put(key, setting) } })
                }
                value.expectedRevision?.let { put("expected_revision", it) }
            },
        )
    }

    override fun deserialize(decoder: Decoder): ServiceCredentialSetCommand =
        throw SerializationException("Service credential writes are write-only")
}
