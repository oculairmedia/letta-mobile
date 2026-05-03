package com.letta.mobile.platform.systemaccess

import com.letta.mobile.bot.tools.HostToolApprovalMetadata
import com.letta.mobile.bot.tools.HostToolApprovalPolicy
import com.letta.mobile.bot.tools.HostToolRiskLevel

/** Bridges system-access capability policy metadata into the bot host-tool approval layer. */
fun SystemAccessCapability.toHostToolApprovalMetadata(toolId: String): HostToolApprovalMetadata? {
    if (toolId !in toolIds) return null
    return HostToolApprovalMetadata(
        policy = approvalPolicy.toHostToolApprovalPolicy(),
        riskLevel = policyRisk.level.toHostToolRiskLevel(),
        capabilityId = id,
        operation = toolId,
        redactedArgumentNames = auditPolicy.redactedFields.toSet(),
        auditEnabled = auditPolicy.loggedFields.isNotEmpty(),
    )
}

private fun SystemAccessApprovalPolicy.toHostToolApprovalPolicy(): HostToolApprovalPolicy = when (this) {
    SystemAccessApprovalPolicy.None -> HostToolApprovalPolicy.None
    SystemAccessApprovalPolicy.AskEveryTime -> HostToolApprovalPolicy.AskEveryTime
    SystemAccessApprovalPolicy.RememberPerSession -> HostToolApprovalPolicy.RememberPerSession
    SystemAccessApprovalPolicy.RememberPerScope -> HostToolApprovalPolicy.RememberPerScope
    SystemAccessApprovalPolicy.Forbidden -> HostToolApprovalPolicy.Forbidden
}

private fun SystemAccessPolicyRiskLevel.toHostToolRiskLevel(): HostToolRiskLevel = when (this) {
    SystemAccessPolicyRiskLevel.Low -> HostToolRiskLevel.Low
    SystemAccessPolicyRiskLevel.Medium -> HostToolRiskLevel.Medium
    SystemAccessPolicyRiskLevel.High -> HostToolRiskLevel.High
    SystemAccessPolicyRiskLevel.VeryHigh -> HostToolRiskLevel.VeryHigh
    SystemAccessPolicyRiskLevel.NotPlayCompatible -> HostToolRiskLevel.Root
}
