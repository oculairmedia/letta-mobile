package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Seconds elapsed since [startedAtEpochMs], ticking once a second while [active].
 *
 * With no start timestamp the clock counts up from zero for the duration of the active
 * window instead, so a run whose first message carries no parseable timestamp still shows
 * movement. Shared by the composer's thinking token and the tool-run summary row so both
 * read the same clock for the same run.
 */
@Composable
internal fun rememberRunElapsedSeconds(active: Boolean, startedAtEpochMs: Long?): State<Long> =
    produceState(0L, active, startedAtEpochMs) {
        value = elapsedSecondsSince(startedAtEpochMs) ?: 0L
        while (active) {
            delay(1_000L)
            value = elapsedSecondsSince(startedAtEpochMs) ?: (value + 1L)
        }
    }

private fun elapsedSecondsSince(startedAtEpochMs: Long?): Long? =
    startedAtEpochMs?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0L)) / 1_000L }
