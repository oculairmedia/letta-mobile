package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Encoder-boundary secret input. Identity equality/hash semantics and a redacted [toString]
 * keep the value out of generated value semantics, snapshots, and diagnostics.
 */
class SecretWriteValue(private val value: String) {
    internal fun revealForEncoding(): String = value

    override fun toString(): String = "<secret withheld>"
}

/** Write-only custom-header values; only header names are safe to inspect. */
class SecretHeadersWriteValue(headers: Map<String, String>) {
    private val values = headers.toMap()

    val names: Set<String> get() = values.keys

    internal fun revealForEncoding(): Map<String, String> = values

    override fun toString(): String = "<${values.size} secret header value(s) withheld>"
}

/**
 * Write-only command to create a provider instance. This deliberately is not a data class:
 * secret inputs have no generated equality, hash, copy, or component methods.
 */
@Serializable(with = CreateProviderInstanceCommandDtoSerializer::class)
class CreateProviderInstanceCommandDto(
    val hostId: String,
    val definitionId: String,
    val displayName: String,
    val baseUrl: String? = null,
    private val initialApiKey: SecretWriteValue? = null,
    private val customHeaders: SecretHeadersWriteValue? = null,
) {
    init {
        require(baseUrl == null || baseUrl.isCredentialFreeWireUrl()) {
            "Provider base URL must not contain credential-bearing URL components"
        }
    }

    val contractVersion: Int get() = PROVIDER_MANAGEMENT_CONTRACT_VERSION

    internal fun apiKeyForEncoding(): String? = initialApiKey?.revealForEncoding()
    internal fun headersForEncoding(): Map<String, String> = customHeaders?.revealForEncoding().orEmpty()

    override fun toString(): String =
        "CreateProviderInstanceCommandDto(contractVersion=$contractVersion, hostId=$hostId, " +
            "definitionId=$definitionId, displayName=$displayName, baseUrl=$baseUrl, " +
            "initialApiKey=${initialApiKey ?: "null"}, customHeaders=${customHeaders ?: "none"})"
}

object CreateProviderInstanceCommandDtoSerializer : KSerializer<CreateProviderInstanceCommandDto> {
    override val descriptor = buildClassSerialDescriptor("CreateProviderInstanceCommandDto") {
        element<Int>("contract_version")
        element<String>("host_id")
        element<String>("definition_id")
        element<String>("display_name")
        element<String?>("base_url", isOptional = true)
        element<String?>("initial_api_key", isOptional = true)
        element<Map<String, String>>("custom_headers", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: CreateProviderInstanceCommandDto) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, 0, value.contractVersion)
        output.encodeStringElement(descriptor, 1, value.hostId)
        output.encodeStringElement(descriptor, 2, value.definitionId)
        output.encodeStringElement(descriptor, 3, value.displayName)
        value.baseUrl?.let { output.encodeStringElement(descriptor, 4, it) }
        value.apiKeyForEncoding()?.let { output.encodeStringElement(descriptor, 5, it) }
        val headers = value.headersForEncoding()
        if (headers.isNotEmpty()) {
            output.encodeSerializableElement(descriptor, 6, SecretHeaderMapSerializer, headers)
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): CreateProviderInstanceCommandDto {
        throw SerializationException("Provider create commands are write-only")
    }
}

private object SecretHeaderMapSerializer : KSerializer<Map<String, String>> by
    MapSerializer(String.serializer(), String.serializer())

/** Non-secret provider metadata update. Header values use a separate credential mutation path. */
@Serializable
data class UpdateProviderInstanceCommandDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
) {
    init {
        require(baseUrl == null || baseUrl.isCredentialFreeWireUrl()) {
            "Provider base URL must not contain credential-bearing URL components"
        }
    }
}

/** Write-only credential replacement with no generated value semantics. */
@Serializable(with = ReplaceProviderCredentialCommandDtoSerializer::class)
class ReplaceProviderCredentialCommandDto(
    val hostId: String,
    val instanceId: String,
    val expectedRevision: String? = null,
    private val apiKey: SecretWriteValue,
) {
    val contractVersion: Int get() = PROVIDER_MANAGEMENT_CONTRACT_VERSION

    internal fun apiKeyForEncoding(): String = apiKey.revealForEncoding()

    override fun toString(): String =
        "ReplaceProviderCredentialCommandDto(contractVersion=$contractVersion, hostId=$hostId, " +
            "instanceId=$instanceId, expectedRevision=$expectedRevision, apiKey=$apiKey)"
}

object ReplaceProviderCredentialCommandDtoSerializer : KSerializer<ReplaceProviderCredentialCommandDto> {
    override val descriptor = buildClassSerialDescriptor("ReplaceProviderCredentialCommandDto") {
        element<Int>("contract_version")
        element<String>("host_id")
        element<String>("instance_id")
        element<String?>("expected_revision", isOptional = true)
        element<String>("api_key")
    }

    override fun serialize(encoder: Encoder, value: ReplaceProviderCredentialCommandDto) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, 0, value.contractVersion)
        output.encodeStringElement(descriptor, 1, value.hostId)
        output.encodeStringElement(descriptor, 2, value.instanceId)
        value.expectedRevision?.let { output.encodeStringElement(descriptor, 3, it) }
        output.encodeStringElement(descriptor, 4, value.apiKeyForEncoding())
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ReplaceProviderCredentialCommandDto {
        throw SerializationException("Provider credential commands are write-only")
    }
}

@Serializable
data class ClearProviderCredentialCommandDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
)

@Serializable(with = ModelVisibilityWireValueSerializer::class)
sealed interface ModelVisibilityWireValue {
    val wireValue: String

    data object Visible : ModelVisibilityWireValue { override val wireValue = "visible" }
    data object Hidden : ModelVisibilityWireValue { override val wireValue = "hidden" }
    data object Automatic : ModelVisibilityWireValue { override val wireValue = "auto" }
    data object Unknown : ModelVisibilityWireValue { override val wireValue = "unknown" }

    companion object {
        fun fromWire(raw: String): ModelVisibilityWireValue = when (raw.trim().lowercase()) {
            "visible" -> Visible
            "hidden" -> Hidden
            "auto", "automatic" -> Automatic
            else -> Unknown
        }
    }
}

object ModelVisibilityWireValueSerializer : KSerializer<ModelVisibilityWireValue> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "ModelVisibilityWireValue",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: ModelVisibilityWireValue) {
        if (value === ModelVisibilityWireValue.Unknown) {
            throw SerializationException("Unknown model visibility cannot be sent")
        }
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): ModelVisibilityWireValue =
        ModelVisibilityWireValue.fromWire(decoder.decodeString())
}

@Serializable
data class SetModelVisibilityCommandDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("host_id") val hostId: String,
    @SerialName("route_id") val routeId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("visibility") val visibility: ModelVisibilityWireValue,
)

@Serializable
data class SetProviderEnabledCommandDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("enabled") val enabled: Boolean,
)

@Serializable
data class DeleteProviderInstanceCommandDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
)

@Serializable
data class ProviderMutationResponseDto(
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("success") val success: Boolean,
    @SerialName("revision") val revision: String? = null,
    @SerialName("instance") val instance: RedactedProviderInstanceDto? = null,
    @SerialName("error") val error: ProviderManagementErrorDto? = null,
    @SerialName("mutation_capability") val mutationCapability: ProviderMutationCapability = ProviderMutationCapability.Denied,
)

private fun String.isCredentialFreeWireUrl(): Boolean {
    if ('?' in this || '#' in this) return false
    val authorityStart = indexOf("://").let { if (it < 0) 0 else it + 3 }
    val pathStart = indexOf('/', authorityStart).let { if (it < 0) length else it }
    return '@' !in substring(authorityStart, pathStart)
}
