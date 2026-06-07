package com.letta.mobile.data.a2ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Currency
import java.util.Locale

internal actual object A2uiBindingFormatters {
    actual fun formatNumber(
        value: Double,
        minimumFractionDigits: Int,
        maximumFractionDigits: Int,
    ): String {
        val formatter = NumberFormat.getNumberInstance(Locale.US)
        formatter.minimumFractionDigits = minimumFractionDigits
        formatter.maximumFractionDigits = maximumFractionDigits
        return formatter.format(value)
    }

    actual fun formatCurrency(
        value: Double,
        currencyCode: String,
    ): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
        runCatching { Currency.getInstance(currencyCode) }.getOrNull()?.let { formatter.currency = it }
        return formatter.format(value)
    }

    actual fun formatDate(
        value: String,
        pattern: String,
    ): String {
        val formatter = runCatching { DateTimeFormatter.ofPattern(pattern) }.getOrElse { DateTimeFormatter.ISO_LOCAL_DATE }
        val temporal = parseDateTime(value) ?: return value
        return formatter.format(temporal)
    }

    private fun parseDateTime(value: String): TemporalAccessor? =
        runCatching { Instant.parse(value).atZone(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME) }.getOrNull()
            ?: runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
}
