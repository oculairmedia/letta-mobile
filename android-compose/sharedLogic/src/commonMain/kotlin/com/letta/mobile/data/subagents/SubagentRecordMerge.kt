package com.letta.mobile.data.subagents

internal fun newerActivity(
    current: com.letta.mobile.data.model.SubagentActivitySnapshot?,
    incoming: com.letta.mobile.data.model.SubagentActivitySnapshot?,
    currentGeneration: Long,
    incomingGeneration: Long,
): com.letta.mobile.data.model.SubagentActivitySnapshot? {
    if (incoming == null) return current
    if (current == null) return incoming
    val currentAt = current.updatedAt
    val incomingAt = incoming.updatedAt
    return when {
        currentAt == null && incomingAt == null ->
            if (incomingGeneration >= currentGeneration) incoming else current
        currentAt == null -> incoming
        incomingAt == null -> current
        incomingAt >= currentAt -> incoming
        else -> current
    }
}
