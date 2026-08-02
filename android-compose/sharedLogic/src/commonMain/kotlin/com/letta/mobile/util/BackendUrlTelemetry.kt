package com.letta.mobile.util

/** Returns a stable backend descriptor without exposing endpoint details. */
fun backendUrlTelemetryDescriptor(url: String?): String {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return "unknown"

    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return "unknown"
    val scheme = value.substring(0, schemeEnd).lowercase()
    if (!scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) {
        return "unknown"
    }

    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .let { if (it < 0) value.length else it }
    val authority = value.substring(authorityStart, authorityEnd)
    if (authority.isBlank()) return "unknown"

    // FNV-1a over UTF-16 code units is deterministic and available in commonMain.
    var hash = 0x811c9dc5u
    for (character in authority) {
        hash = (hash xor character.code.toUInt()) * 0x01000193u
    }
    val shortHash = hash.toString(16).padStart(8, '0').takeLast(6)
    return "$scheme://$shortHash"
}
