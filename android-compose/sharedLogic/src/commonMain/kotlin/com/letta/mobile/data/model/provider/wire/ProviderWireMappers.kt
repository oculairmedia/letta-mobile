package com.letta.mobile.data.model.provider.wire

import com.letta.mobile.data.model.CatalogRevision
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderFieldId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.ProviderRevision
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.CredentialStatus
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.OperationalStatus
import com.letta.mobile.data.model.provider.ProviderDefinition
import com.letta.mobile.data.model.provider.ProviderFieldSchema
import com.letta.mobile.data.model.provider.ProviderProtocol
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlinx.collections.immutable.toPersistentList

/**
 * Exception thrown when a wire payload's `host_id` does not match the active host context.
 */
class HostMismatchException(
    val expectedHostId: HostId,
    val actualHostId: HostId,
) : IllegalArgumentException("Payload host_id '$actualHostId' does not match active host '$expectedHostId'")

fun ProviderFieldSchemaDto.toDomain(): ProviderFieldSchema = ProviderFieldSchema(
    id = ProviderFieldId(id),
    label = label,
    description = description,
    isSecret = isSecret,
    isRequired = isRequired,
    placeholder = placeholder,
)

fun ProviderFieldSchema.toDto(): ProviderFieldSchemaDto = ProviderFieldSchemaDto(
    id = id.value,
    label = label,
    description = description,
    isSecret = isSecret,
    isRequired = isRequired,
    placeholder = placeholder,
)

fun ProviderDefinitionDto.toDomain(): ProviderDefinition = ProviderDefinition(
    id = ProviderDefinitionId(id),
    displayName = displayName,
    description = description,
    supportedProtocols = supportedProtocols.map { ProviderProtocol.fromWire(it) }.toPersistentList(),
    fields = fields.map { it.toDomain() }.toPersistentList(),
    defaultBaseUrl = defaultBaseUrl,
)

fun ProviderDefinition.toDto(): ProviderDefinitionDto = ProviderDefinitionDto(
    id = id.value,
    displayName = displayName,
    description = description,
    supportedProtocols = supportedProtocols.map { it.wireValue },
    fields = fields.map { it.toDto() },
    defaultBaseUrl = defaultBaseUrl,
)

fun RedactedProviderInstanceDto.toDomain(expectedHostId: HostId? = null): RedactedProviderInstance {
    val payloadHostId = HostId(hostId)
    if (expectedHostId != null && payloadHostId != expectedHostId) {
        throw HostMismatchException(expectedHostId, payloadHostId)
    }
    return RedactedProviderInstance(
        id = ProviderInstanceId(id),
        hostId = payloadHostId,
        definitionId = ProviderDefinitionId(definitionId),
        displayName = displayName,
        baseUrl = baseUrl,
        credentialStatus = CredentialStatus.fromWire(credentialStatus),
        operationalStatus = OperationalStatus.fromWire(operationalStatus),
        revision = revision?.let { ProviderRevision(it) },
        configuredFieldIds = configuredFieldIds.map { ProviderFieldId(it) }.toPersistentList(),
        configuredHeaderNames = configuredHeaderNames.toPersistentList(),
    )
}

fun RedactedProviderInstance.toDto(): RedactedProviderInstanceDto = RedactedProviderInstanceDto(
    id = id.value,
    hostId = hostId.value,
    definitionId = definitionId.value,
    displayName = displayName,
    baseUrl = baseUrl,
    credentialStatus = credentialStatus.wireValue,
    operationalStatus = operationalStatus.wireValue,
    revision = revision?.value,
    configuredFieldIds = configuredFieldIds.map { it.value },
    configuredHeaderNames = configuredHeaderNames.toList(),
)

fun ModelRouteDto.toDomain(expectedHostId: HostId? = null): CanonicalModelRoute {
    val payloadHostId = HostId(hostId)
    if (expectedHostId != null && payloadHostId != expectedHostId) {
        throw HostMismatchException(expectedHostId, payloadHostId)
    }
    return CanonicalModelRoute(
        id = ModelRouteId(id),
        hostId = payloadHostId,
        providerInstanceId = ProviderInstanceId(providerInstanceId),
        modelHandle = modelHandle,
        displayName = displayName,
        contextWindowLimit = contextWindowLimit,
        availability = ModelAvailability.fromWire(availability),
        visibility = VisibilityPolicy.fromWire(visibility),
        aliases = aliases.toPersistentList(),
        revision = revision?.let { CatalogRevision(it) },
    )
}

fun CanonicalModelRoute.toDto(): ModelRouteDto = ModelRouteDto(
    id = id.value,
    hostId = hostId.value,
    providerInstanceId = providerInstanceId.value,
    modelHandle = modelHandle,
    displayName = displayName,
    contextWindowLimit = contextWindowLimit,
    availability = availability.wireValue,
    visibility = visibility.wireValue,
    aliases = aliases.toList(),
    revision = revision?.value,
)
