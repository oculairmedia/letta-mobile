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

private const val IROH_SCHEME = "iroh://"
private const val IROH_ID_PREVIEW_CHARS = 8

private fun String.toSelfHostedBackendLabel(): String {
    val trimmed = trim()
    if (trimmed.startsWith(IROH_SCHEME, ignoreCase = true)) return trimmed.toIrohBackendLabel()
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

/**
 * An Iroh address is a node id plus dial hints, or a whole ticket: hundreds of opaque characters
 * that no one reads on a conversation page. Label it by its first dial host when it has one,
 * otherwise by a short preview of the node id or ticket.
 */
private fun String.toIrohBackendLabel(): String {
    val body = substring(IROH_SCHEME.length).trim()
    val firstHost = body.substringAfter('@', missingDelimiterValue = "")
        .substringBefore(',')
        .substringBefore('/')
        .trim()
    if (firstHost.isNotEmpty()) return "Iroh \u00b7 $firstHost"
    val id = body.substringBefore('/').substringBefore('?')
    if (id.isBlank()) return "Iroh"
    val preview = if (id.length > IROH_ID_PREVIEW_CHARS) id.take(IROH_ID_PREVIEW_CHARS) + "\u2026" else id
    return "Iroh \u00b7 $preview"
}
