package com.letta.mobile.ui.chat.surface.timeline.rows

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** The system's clock setting: the user's 24-hour toggle, else the locale's default. */
@Composable
internal actual fun systemUses24HourClock(): Boolean = DateFormat.is24HourFormat(LocalContext.current)
