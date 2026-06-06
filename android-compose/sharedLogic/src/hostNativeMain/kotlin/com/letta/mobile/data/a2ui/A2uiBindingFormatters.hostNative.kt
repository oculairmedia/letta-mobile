package com.letta.mobile.data.a2ui

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

internal actual object A2uiBindingFormatters {
    actual fun formatNumber(
        value: Double,
        minimumFractionDigits: Int,
        maximumFractionDigits: Int,
    ): String = value.formatDecimal(
        minimumFractionDigits = minimumFractionDigits,
        maximumFractionDigits = maximumFractionDigits,
    )

    actual fun formatCurrency(
        value: Double,
        currencyCode: String,
    ): String {
        val normalizedCurrencyCode = currencyCode.uppercase()
        val fractionDigits = when (normalizedCurrencyCode) {
            "JPY", "KRW" -> 0
            else -> 2
        }
        return currencySymbol(normalizedCurrencyCode) + value.formatDecimal(
            minimumFractionDigits = fractionDigits,
            maximumFractionDigits = fractionDigits,
        )
    }

    actual fun formatDate(
        value: String,
        pattern: String,
    ): String {
        val parts = A2uiDateParts.parse(value) ?: return value
        return parts.format(pattern)
    }

    private val IsoDateTimeRegex = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T\s](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?$""")

    private data class A2uiDateParts(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int = 0,
        val minute: Int = 0,
        val second: Int = 0,
    ) {
        fun format(pattern: String): String {
            return pattern
                .replace("yyyy", year.toString().padStart(4, '0'))
                .replace("MM", month.pad2())
                .replace("dd", day.pad2())
                .replace("HH", hour.pad2())
                .replace("mm", minute.pad2())
                .replace("ss", second.pad2())
                .replace("M", month.toString())
                .replace("d", day.toString())
                .replace("H", hour.toString())
                .replace("m", minute.toString())
                .replace("s", second.toString())
        }

        companion object {
            fun parse(value: String): A2uiDateParts? {
                val match = IsoDateTimeRegex.matchEntire(value) ?: return null
                return A2uiDateParts(
                    year = match.groupValues[1].toIntOrNull() ?: return null,
                    month = match.groupValues[2].toIntOrNull() ?: return null,
                    day = match.groupValues[3].toIntOrNull() ?: return null,
                    hour = match.groupValues.getOrNull(4)?.takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0,
                    minute = match.groupValues.getOrNull(5)?.takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0,
                    second = match.groupValues.getOrNull(6)?.takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0,
                )
            }
        }
    }
}

private fun Double.formatDecimal(
    minimumFractionDigits: Int,
    maximumFractionDigits: Int,
): String {
    if (!isFinite()) return toString()
    val minDigits = minimumFractionDigits.coerceAtLeast(0)
    val maxDigits = maximumFractionDigits.coerceAtLeast(minDigits).coerceAtMost(9)
    val factor = 10.0.pow(maxDigits).toLong().coerceAtLeast(1L)
    val scaled = round(abs(this) * factor).toLong()
    val integerText = (scaled / factor).toString().withGroupingSeparators()
    val fractionText = if (maxDigits == 0) {
        ""
    } else {
        (scaled % factor)
            .toString()
            .padStart(maxDigits, '0')
            .trimTrailingZeroes(minDigits)
    }
    val sign = if (this < 0.0 && (scaled / factor > 0 || scaled % factor > 0)) "-" else ""
    return if (fractionText.isEmpty()) {
        "$sign$integerText"
    } else {
        "$sign$integerText.$fractionText"
    }
}

private fun String.withGroupingSeparators(): String =
    reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

private fun String.trimTrailingZeroes(minimumLength: Int): String {
    var text = this
    while (text.length > minimumLength && text.endsWith("0")) {
        text = text.dropLast(1)
    }
    return text
}

private fun currencySymbol(currencyCode: String): String = when (currencyCode) {
    "USD", "CAD", "AUD", "NZD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY", "CNY" -> "¥"
    else -> "$currencyCode "
}

private fun Int.pad2(): String = toString().padStart(2, '0')
