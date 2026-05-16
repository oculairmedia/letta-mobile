package com.letta.mobile.data.model

import java.net.URI

fun LettaConfig?.toBackendLabel(): String? {
    val config = this ?: return null
    return when (config.mode) {
        LettaConfig.Mode.CLOUD -> "Cloud"
        LettaConfig.Mode.SELF_HOSTED -> config.serverUrl.toSelfHostedBackendLabel()
    }
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

private fun parsedHostLabel(value: String): String? = runCatching { URI(value) }
    .getOrNull()
    ?.let { uri ->
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return@let null
        val hostLabel = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        if (uri.port == -1) hostLabel else "$hostLabel:${uri.port}"
    }
