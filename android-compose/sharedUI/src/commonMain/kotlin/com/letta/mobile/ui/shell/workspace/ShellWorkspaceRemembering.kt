package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay

/*
 * What lets the workspace skip a pass in which nothing it draws changed: its state compares equal
 * and its host is the same object. A host that rebuilds either on every pass of its root
 * recomposes the whole shell each time.
 */

/**
 * The wall clock the navigation's relative times ("5m ago") and recency order are read against,
 * moved on once a [period]. A `Clock.System.now()` taken in composition is a new value on every
 * pass, so a navigation built from it never equals the last one.
 */
@Composable
fun rememberCoarseNow(period: Duration = 1.minutes): State<Instant> =
    produceState(Clock.System.now(), period) {
        while (true) {
            delay(period)
            value = Clock.System.now()
        }
    }

/**
 * The workspace host [build] makes, kept until one of [keys] changes. Its slots and verbs read the
 * host's [inputs] through the state they are handed, so a pass with new inputs reaches them
 * without a new host.
 */
@Composable
fun <I> rememberShellWorkspaceHost(
    inputs: I,
    vararg keys: Any?,
    build: (State<I>) -> LettaShellWorkspaceHost,
): LettaShellWorkspaceHost {
    val current = rememberUpdatedState(inputs)
    return remember(*keys) { build(current) }
}

/**
 * Counts the passes of the composable that calls it and, while [enabled], logs them every
 * [period] as "[perf] <label> composed N times". Not restartable, so it runs on every pass of its
 * caller rather than skipping: a root that recomposes at frame rate shows ~60 a second, a root at
 * rest shows none.
 */
@NonRestartableComposable
@Composable
fun ReportCompositionPasses(label: String, enabled: Boolean, period: Duration = 5.seconds) {
    if (!enabled) return
    val passes = remember { CompositionPasses() }
    SideEffect { passes.count++ }
    LaunchedEffect(passes, period) {
        while (true) {
            delay(period)
            println("[perf] $label composed ${passes.count} times in the last $period")
            passes.count = 0
        }
    }
}

private class CompositionPasses {
    var count = 0
}
