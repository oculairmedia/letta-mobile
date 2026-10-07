package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable

/** No locale clock setting to read on the web build: the 12-hour clock. */
@Composable
internal actual fun systemUses24HourClock(): Boolean = false
