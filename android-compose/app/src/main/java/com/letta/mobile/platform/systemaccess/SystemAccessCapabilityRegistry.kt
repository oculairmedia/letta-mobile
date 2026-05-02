package com.letta.mobile.platform.systemaccess

import javax.inject.Inject
import javax.inject.Singleton

interface SystemAccessCapabilityRegistry {
    fun listCapabilities(): List<SystemAccessCapability>
    fun getCapability(id: String): SystemAccessCapability?
    fun checkToolAccess(toolId: String): SystemAccessToolCheck
    fun canExposeTool(toolId: String): Boolean = checkToolAccess(toolId).allowed
}

@Singleton
class DefaultSystemAccessCapabilityRegistry @Inject constructor(
    private val environment: SystemAccessEnvironment,
) : SystemAccessCapabilityRegistry {
    override fun listCapabilities(): List<SystemAccessCapability> =
        SystemAccessCapabilityDefinitions.all.map { it.resolve(environment) }

    override fun getCapability(id: String): SystemAccessCapability? =
        SystemAccessCapabilityDefinitions.all.firstOrNull { it.id == id }?.resolve(environment)

    override fun checkToolAccess(toolId: String): SystemAccessToolCheck {
        val capability = listCapabilities().firstOrNull { toolId in it.toolIds }
        return if (capability == null) {
            SystemAccessToolCheck(
                toolId = toolId,
                allowed = false,
                reason = "No system-access capability declares this tool id.",
            )
        } else {
            capability.toolCheck(toolId)
        }
    }

    private fun SystemAccessCapabilityDefinition.resolve(
        environment: SystemAccessEnvironment,
    ): SystemAccessCapability {
        val flavorAvailability = flavorAvailability.availabilityFor(environment.flavor)
        val probeResult = when (flavorAvailability) {
            SystemAccessFlavorAvailability.Unsupported -> ProbeResult(
                SystemAccessCapabilityStatus.Unavailable,
                "Capability is not supported by the ${environment.flavor.name.lowercase()} flavor.",
            )
            SystemAccessFlavorAvailability.PolicyGated -> probe.status(environment).let { result ->
                if (environment.flavor == com.letta.mobile.platform.SystemAccessFlavor.Play) {
                    result.copy(reason = "Policy-gated for Play. ${result.reason}")
                } else {
                    result
                }
            }
            SystemAccessFlavorAvailability.DocumentationOnly,
            SystemAccessFlavorAvailability.Supported,
            -> probe.status(environment)
        }

        return SystemAccessCapability(
            definition = this,
            status = probeResult.status,
            statusReason = probeResult.reason,
            userEnabled = defaultUserEnabled && probeResult.status != SystemAccessCapabilityStatus.Unavailable,
        )
    }

    private fun SystemAccessCapability.toolCheck(toolId: String): SystemAccessToolCheck {
        val denialReason = when {
            !userEnabled -> "Capability is disabled by user policy or default safety posture."
            approvalPolicy == SystemAccessApprovalPolicy.Forbidden -> "Capability approval policy forbids tool exposure."
            !isUsableForTools -> statusReason
            else -> null
        }

        return if (denialReason == null) {
            SystemAccessToolCheck(
                toolId = toolId,
                allowed = true,
                capabilityId = id,
                status = status,
                reason = "Capability is granted and enabled.",
            )
        } else {
            deniedToolCheck(toolId, denialReason)
        }
    }

    private fun SystemAccessCapability.deniedToolCheck(
        toolId: String,
        reason: String,
    ) = SystemAccessToolCheck(
        toolId = toolId,
        allowed = false,
        capabilityId = id,
        status = status,
        reason = reason,
    )
}
