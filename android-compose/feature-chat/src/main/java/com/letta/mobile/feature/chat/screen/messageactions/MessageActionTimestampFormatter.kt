package com.letta.mobile.feature.chat.screen.messageactions

import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun formatMessageActionTimestamp(
    timestamp: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val epochMillis = parseTimestampEpochMillis(timestamp) ?: return null
    return DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(epochMillis))
}
