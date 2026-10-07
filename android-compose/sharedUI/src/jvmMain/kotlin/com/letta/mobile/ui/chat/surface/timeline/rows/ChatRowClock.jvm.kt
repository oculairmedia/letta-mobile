package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** The default locale's short time pattern decides: "HH:mm" (de, en-GB) is 24-hour, "h:mm a" is not. */
@Composable
internal actual fun systemUses24HourClock(): Boolean = remember { localeUses24HourClock(Locale.getDefault(Locale.Category.FORMAT)) }

internal fun localeUses24HourClock(locale: Locale): Boolean {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
    return pattern.contains('H') || pattern.contains('k')
}
