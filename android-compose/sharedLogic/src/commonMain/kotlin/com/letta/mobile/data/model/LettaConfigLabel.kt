package com.letta.mobile.data.model

fun LettaConfig?.toBackendLabel(): String? {
    val config = this ?: return null
    return when (config.mode) {
        LettaConfig.Mode.CLOUD -> "Cloud"
        LettaConfig.Mode.SELF_HOSTED -> config.serverUrl.toSelfHostedBackendLabel()
        LettaConfig.Mode.LOCAL -> config.serverUrl.toLocalRuntimeLabel()
    }
}

private fun String.toLocalRuntimeLabel(): String = when (localRuntimeScheme()) {
    "local-koog" -> "Local Koog runtime"
    else -> "Local LettaCode"
}

private fun String.toSelfHostedBackendLabel(): String {
    val trimmed = trim()
    val parsedLabel = parsedHostLabel(trimmed)
        ?: parsedHostLabel("https://$trimmed")
    val fallbackLabel = trimmed
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringAfter('@')

    return (parsedLabel ?: fallbackLabel).ifBlank { "Server" }
}

private fun parsedHostLabel(value: String): String? {
    val authority = value
        .trim()
        .substringAfter("://", value.trim())
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
        .takeIf { it.isNotBlank() }
        ?: return null

    return when {
        authority.startsWith("[") -> authority
        authority.count { it == ':' } > 1 -> "[$authority]"
        else -> authority
    }
}

private fun String.localRuntimeScheme(): String =
    trim().substringBefore("://", missingDelimiterValue = trim()).lowercase()
