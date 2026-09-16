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

/** Secret-safe typed wire failure. No payload-controlled text is included in diagnostics. */
open class ProviderWireContractException(
    val reason: Reason,
) : IllegalArgumentException(reason.diagnostic) {
    enum class Reason(val diagnostic: String) {
        MalformedPayload("Malformed provider-management payload"),
        UnsupportedVersion("Unsupported provider-management contract version"),
        HostMismatch("Provider payload host does not match active host"),
    }
}

class HostMismatchException : ProviderWireContractException(Reason.HostMismatch)

private fun requireSupportedContract(contractVersion: Int) {
    if (contractVersion != PROVIDER_MANAGEMENT_CONTRACT_VERSION) {
        throw ProviderWireContractException(ProviderWireContractException.Reason.UnsupportedVersion)
    }
}

private fun requireHost(expected: HostId, actual: String) {
    if (actual != expected.value) throw HostMismatchException()
}

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
    supportedProtocols = supportedProtocols.map { raw ->
        ProviderProtocol.fromWire(raw).withoutUntrustedRaw()
    }.toPersistentList(),
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

fun RedactedProviderInstanceDto.toDomain(expectedHostId: HostId): RedactedProviderInstance {
    requireHost(expectedHostId, hostId)
    return toDomainAfterHostValidation(expectedHostId)
}

private fun RedactedProviderInstanceDto.toDomainAfterHostValidation(host: HostId): RedactedProviderInstance =
    RedactedProviderInstance(
        id = ProviderInstanceId(id),
        hostId = host,
        definitionId = ProviderDefinitionId(definitionId),
        displayName = displayName,
        baseUrl = baseUrl,
        credentialStatus = CredentialStatus.fromWire(credentialStatus).withoutUntrustedRaw(),
        operationalStatus = OperationalStatus.fromWire(operationalStatus).withoutUntrustedRaw(),
        revision = revision?.let(::ProviderRevision),
        configuredFieldIds = configuredFieldIds.map(::ProviderFieldId).toPersistentList(),
        configuredHeaderNames = configuredHeaderNames.toPersistentList(),
    )

fun ProviderInstancesListResponseDto.toDomain(expectedHostId: HostId): List<RedactedProviderInstance> {
    requireSupportedContract(contractVersion)
    requireHost(expectedHostId, hostId)
    instances.forEach { requireHost(expectedHostId, it.hostId) }
    return instances.map { it.toDomainAfterHostValidation(expectedHostId) }
}

fun ProviderInstanceResponseDto.toDomain(expectedHostId: HostId): RedactedProviderInstance {
    requireSupportedContract(contractVersion)
    requireHost(expectedHostId, instance.hostId)
    return instance.toDomainAfterHostValidation(expectedHostId)
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

fun ModelRouteDto.toDomain(expectedHostId: HostId): CanonicalModelRoute {
    requireHost(expectedHostId, hostId)
    return toDomainAfterHostValidation(expectedHostId)
}

private fun ModelRouteDto.toDomainAfterHostValidation(host: HostId): CanonicalModelRoute =
    CanonicalModelRoute(
        id = ModelRouteId(id),
        hostId = host,
        providerInstanceId = ProviderInstanceId(providerInstanceId),
        modelHandle = modelHandle,
        displayName = displayName,
        contextWindowLimit = contextWindowLimit,
        availability = ModelAvailability.fromWire(availability).withoutUntrustedRaw(),
        visibility = VisibilityPolicy.fromWire(visibility).withoutUntrustedRaw(),
        aliases = aliases.toPersistentList(),
        revision = revision?.let(::CatalogRevision),
    )

fun ModelRoutesListResponseDto.toDomain(expectedHostId: HostId): List<CanonicalModelRoute> {
    requireSupportedContract(contractVersion)
    requireHost(expectedHostId, hostId)
    routes.forEach { requireHost(expectedHostId, it.hostId) }
    return routes.map { it.toDomainAfterHostValidation(expectedHostId) }
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

private fun ProviderProtocol.withoutUntrustedRaw(): ProviderProtocol =
    if (this is ProviderProtocol.Unknown) ProviderProtocol.Unknown("unknown") else this

private fun CredentialStatus.withoutUntrustedRaw(): CredentialStatus =
    if (this is CredentialStatus.Unknown) CredentialStatus.Unknown("unknown") else this

private fun OperationalStatus.withoutUntrustedRaw(): OperationalStatus =
    if (this is OperationalStatus.Unknown) OperationalStatus.Unknown("unknown") else this

private fun ModelAvailability.withoutUntrustedRaw(): ModelAvailability =
    if (this is ModelAvailability.Unknown) ModelAvailability.Unknown("unknown") else this

private fun VisibilityPolicy.withoutUntrustedRaw(): VisibilityPolicy =
    if (this is VisibilityPolicy.Unknown) VisibilityPolicy.Unknown("unknown") else this
