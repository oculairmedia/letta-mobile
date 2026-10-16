package com.letta.mobile.data.repository

// The unified pinned-items order stores each entry as `shortcut:<name>` or `agent:<id>`.

internal fun shortcutKeyPart(name: String) = "shortcut:$name"

internal fun agentKeyPart(id: String) = "agent:$id"

internal fun isShortcutKeyPart(key: String) = key.startsWith("shortcut:")

internal fun isAgentKeyPart(key: String) = key.startsWith("agent:")

internal fun parseShortcutKeyPart(key: String): String? =
    if (isShortcutKeyPart(key)) key.removePrefix("shortcut:") else null

internal fun parseAgentKeyPart(key: String): String? =
    if (isAgentKeyPart(key)) key.removePrefix("agent:") else null
