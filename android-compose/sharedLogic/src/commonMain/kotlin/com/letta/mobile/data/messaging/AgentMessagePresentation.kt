package com.letta.mobile.data.messaging

fun agentMessageDisplayLabel(agentId: String, resolvedName: String?): String {
    val name = resolvedName?.trim()
    if (!name.isNullOrEmpty()) return name
    if (agentId.isBlank()) return "Unknown agent"
    return "Agent " + agentId.removePrefix("agent-").take(8)
}

fun AgentMessageProvenance.compactLabel(resolveName: (String) -> String?): String {
    val from = agentMessageDisplayLabel(fromAgentId, fromAgentName ?: resolveName(fromAgentId))
    val to = agentMessageDisplayLabel(toAgentId, toAgentName ?: resolveName(toAgentId))
    return "$from → $to · Agent message"
}

fun AgentMessageDeliveryState.displayLabel(): String = when (this) {
    AgentMessageDeliveryState.PENDING -> "Pending"
    AgentMessageDeliveryState.SENT -> "Sent"
    AgentMessageDeliveryState.RECEIVER_CONFIRMED -> "Delivered"
    AgentMessageDeliveryState.FAILED -> "Failed"
}
