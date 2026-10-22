package com.letta.mobile.data.repository

// The pinned-items order stores each pinned agent as `agent:<id>`.

internal fun agentKeyPart(id: String) = "agent:$id"

internal fun isAgentKeyPart(key: String) = key.startsWith("agent:")

internal fun parseAgentKeyPart(key: String): String? =
    if (isAgentKeyPart(key)) key.removePrefix("agent:") else null
