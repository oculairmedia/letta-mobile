package com.letta.mobile.ui.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The wall clock, re-read once a second while [active] (a countdown is on
 * screen) and left alone otherwise, so idle pairing screens do not tick.
 * Stateless composables take the returned value as `nowMs`.
 */
@Composable
internal fun rememberTickingNow(clock: () -> Long, active: Boolean): Long {
    var nowMs by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(active) {
        nowMs = clock()
        while (active) {
            delay(PAIRING_TICK_MS)
            nowMs = clock()
        }
    }
    return nowMs
}

internal const val PAIRING_TICK_MS = 1_000L
