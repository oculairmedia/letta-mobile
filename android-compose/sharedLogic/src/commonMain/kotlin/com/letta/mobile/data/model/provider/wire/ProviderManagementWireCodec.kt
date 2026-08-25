package com.letta.mobile.data.model.provider.wire

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.ProviderDefinition
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single production JSON policy for provider-management transport.
 *
 * V1 accepts additive unknown fields for forward compatibility, emits declared defaults for a
 * deterministic wire shape, omits nulls, rejects malformed input, and gates every top-level
 * response before mapping it into state.
 */
object ProviderManagementWireCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
        coerceInputValues = false
    }

    fun encode(command: CreateProviderInstanceCommandDto): String =
        encode(CreateProviderInstanceCommandDto.serializer(), command)

    fun encode(command: ReplaceProviderCredentialCommandDto): String =
        encode(ReplaceProviderCredentialCommandDto.serializer(), command)

    fun encode(command: UpdateProviderInstanceCommandDto): String =
        encodeVersionedCommand(command.contractVersion, command)

    fun encode(command: ClearProviderCredentialCommandDto): String =
        encodeVersionedCommand(command.contractVersion, command)

    fun encode(command: SetModelVisibilityCommandDto): String =
        encodeVersionedCommand(command.contractVersion, command)

    fun encode(command: SetProviderEnabledCommandDto): String =
        encodeVersionedCommand(command.contractVersion, command)

    fun encode(command: DeleteProviderInstanceCommandDto): String =
        encodeVersionedCommand(command.contractVersion, command)

    fun decodeDefinitions(payload: String): List<ProviderDefinition> {
        val response = decode(ProviderDefinitionsListResponseDto.serializer(), payload)
        requireVersion(response.contractVersion)
        return response.definitions.map { it.toDomain() }
    }

    fun decodeInstances(payload: String, activeHostId: HostId): List<RedactedProviderInstance> =
        decode(ProviderInstancesListResponseDto.serializer(), payload).toDomain(activeHostId)

    fun decodeInstance(payload: String, activeHostId: HostId): RedactedProviderInstance =
        decode(ProviderInstanceResponseDto.serializer(), payload).toDomain(activeHostId)

    fun decodeRoutes(payload: String, activeHostId: HostId): List<CanonicalModelRoute> =
        decode(ModelRoutesListResponseDto.serializer(), payload).toDomain(activeHostId)

    fun decodeMutationResponse(payload: String, activeHostId: HostId): ProviderMutationResponseDto {
        val response = decode(ProviderMutationResponseDto.serializer(), payload)
        requireVersion(response.contractVersion)
        response.instance?.toDomain(activeHostId)
        return response.copy(error = response.error?.redacted())
    }

    internal fun encodeDefinitionsForTest(response: ProviderDefinitionsListResponseDto): String =
        encode(ProviderDefinitionsListResponseDto.serializer(), response)

    internal fun encodeInstancesForTest(response: ProviderInstancesListResponseDto): String =
        encode(ProviderInstancesListResponseDto.serializer(), response)

    internal fun encodeRoutesForTest(response: ModelRoutesListResponseDto): String =
        encode(ModelRoutesListResponseDto.serializer(), response)

    internal fun encodeErrorForTest(error: ProviderManagementErrorDto): String =
        encode(ProviderManagementErrorDto.serializer(), error.redacted())

    internal fun decodeErrorForTest(payload: String): ProviderManagementErrorDto =
        decode(ProviderManagementErrorDto.serializer(), payload).redacted()

    private inline fun <reified T> encodeVersionedCommand(version: Int, command: T): String =
        try {
            if (version != PROVIDER_MANAGEMENT_CONTRACT_VERSION) {
                throw IllegalArgumentException("Unsupported request contract version")
            }
            json.encodeToString(command)
        } catch (failure: Exception) {
            throw ProviderWireEncodingException(failure)
        }

    private fun <T> encode(strategy: SerializationStrategy<T>, value: T): String =
        try {
            json.encodeToString(strategy, value)
        } catch (failure: Exception) {
            throw ProviderWireEncodingException(failure)
        }

    private fun <T> decode(strategy: DeserializationStrategy<T>, payload: String): T =
        try {
            json.decodeFromString(strategy, payload)
        } catch (failure: ProviderWireContractException) {
            throw failure
        } catch (failure: Exception) {
            throw ProviderWireContractException(ProviderWireContractException.Reason.MalformedPayload)
        }

    private fun requireVersion(version: Int) {
        if (version != PROVIDER_MANAGEMENT_CONTRACT_VERSION) {
            throw ProviderWireContractException(ProviderWireContractException.Reason.UnsupportedVersion)
        }
    }
}

/** Encoding failures never include request objects, payloads, or serializer diagnostics. */
class ProviderWireEncodingException internal constructor(
    @Suppress("UNUSED_PARAMETER") cause: Exception,
) : IllegalArgumentException("Provider-management request could not be encoded")
