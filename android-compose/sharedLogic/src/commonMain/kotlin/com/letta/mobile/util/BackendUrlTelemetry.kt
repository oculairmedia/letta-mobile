package com.letta.mobile.util

/** Returns a fixed backend category without exposing endpoint details. */
fun backendUrlTelemetryDescriptor(url: String?): String {
    val scheme = url
        ?.trim()
        ?.substringBefore("://", missingDelimiterValue = "")
        ?.lowercase()
        .orEmpty()
    return when (scheme) {
        "iroh", "https", "http" -> scheme
        else -> "unknown"
    }
}
