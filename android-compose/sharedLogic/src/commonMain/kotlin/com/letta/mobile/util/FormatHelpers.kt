package com.letta.mobile.util

import kotlin.math.roundToInt

/**
 * Common platform-neutral formatting helpers for tokens, bytes, durations, and USD amounts.
 */
object FormatHelpers {

    /**
     * Formats integer count into compact representation (e.g., 987 -> "987", 12345 -> "12.3k", 999999 -> "1M").
     */
    fun formatCompactCount(value: Int): String {
        if (value < 9_950) return value.toString()
        val inThousands = (value / 100.0).roundToInt() / 10.0
        if (inThousands >= 1000.0) {
            val inMillions = (value / 100_000.0).roundToInt() / 10.0
            return "${inMillions}M"
        }
        return "${inThousands}k"
    }

    /**
     * Formats byte size into human readable string (e.g. 500 B, 4.2 KB, 1.5 MB, 3.0 GB).
     * Always one decimal place for KB/MB/GB to keep widths stable in UI.
     */
    fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = (bytes / 102.4).roundToInt() / 10.0
        if (kb >= 1024.0 * 1024.0) {
            val gb = (bytes / (1024.0 * 1024.0 * 1024.0 / 10.0)).roundToInt() / 10.0
            return "${formatOneDecimal(gb)} GB"
        }
        if (kb >= 1024.0) {
            val mb = (bytes / (1024.0 * 1024.0 / 10.0)).roundToInt() / 10.0
            return "${formatOneDecimal(mb)} MB"
        }
        return "${formatOneDecimal(kb)} KB"
    }

    private fun formatOneDecimal(value: Double): String {
        // Manual formatting — KMP-safe (no String.format which is JVM-only).
        // Truncates to one decimal place.
        val negative = value < 0
        val absValue = if (negative) -value else value
        val intPart = absValue.toLong()
        val tenths = ((absValue - intPart) * 10).toLong()
        val sign = if (negative) "-" else ""
        return "$sign$intPart.$tenths"
    }

    /**
     * Formats duration in milliseconds to value string ("—" when <= 0).
     */
    fun formatDurationValue(durationMs: Long): String = when {
        durationMs <= 0L -> "—"
        durationMs < 1_000L -> durationMs.toString()
        durationMs < 60_000L -> {
            val sec = (durationMs / 100.0).roundToInt() / 10.0
            "$sec"
        }
        else -> {
            val min = (durationMs / 6000.0).roundToInt() / 10.0
            "$min"
        }
    }

    /**
     * Formats duration unit suffix ("" when <= 0).
     */
    fun formatDurationSuffix(durationMs: Long): String = when {
        durationMs <= 0L -> ""
        durationMs < 1_000L -> "ms"
        durationMs < 60_000L -> "s"
        else -> "m"
    }

    /**
     * Truncates provider prefix from model identifier (e.g. "openai/gpt-4o" -> "gpt-4o").
     */
    fun truncateModelId(model: String): String {
        val lastSlash = model.lastIndexOf('/')
        return if (lastSlash >= 0 && lastSlash < model.length - 1) model.substring(lastSlash + 1) else model
    }
}
