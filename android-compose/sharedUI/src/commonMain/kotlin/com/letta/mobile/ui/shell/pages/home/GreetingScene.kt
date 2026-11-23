package com.letta.mobile.ui.shell.pages.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.home.greeting.GreetingInput
import com.letta.mobile.data.home.greeting.GreetingPlay
import com.letta.mobile.data.home.greeting.GreetingState
import com.letta.mobile.data.home.greeting.GreetingTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * One greeting on screen: the reducer's [state], the chance it plays with, and where each actor is
 * in the window ([rects]) so looks and hops can be aimed. Inputs go in through [send]; nothing else
 * writes the state.
 */
internal class GreetingScene(seed: Int, booted: Boolean) {
    private val random = Random(seed)

    var state: GreetingState by mutableStateOf(GreetingState(booted = booted))
        private set

    /** Window bounds of every actor drawn: the mascots and the mark. */
    val rects = mutableStateMapOf<GreetingTarget, Rect>()

    fun send(input: GreetingInput) {
        state = GreetingPlay.reduce(state, input, random)
    }

    fun place(target: GreetingTarget, bounds: Rect) {
        if (rects[target] != bounds) rects[target] = bounds
    }
}

/**
 * Whether the mark has played its entrance in this process: the first Home of a launch boots it,
 * coming back to Home later is a return.
 */
internal object GreetingLaunch {
    var booted: Boolean = false
}

@Composable
internal fun rememberGreetingScene(): GreetingScene = remember { GreetingScene(seed = Random.nextInt(), booted = GreetingLaunch.booted) }

/**
 * The greeting's clock: frames tick the reducer only while something is playing ([GreetingState.active]).
 * At rest it asks for no frames; after a quiet spell it hands the reducer a [GreetingInput.Quiet], so a
 * resting crew still turns to each other now and then.
 */
@Composable
internal fun GreetingClock(scene: GreetingScene) {
    LaunchedEffect(scene) {
        snapshotFlow { scene.state.active }.collectLatest { active ->
            // At rest: a quiet moment now and then, until something plays (which cancels this).
            while (!active) {
                delay(QUIET_SPELL + QUIET_JITTER * Random.nextDouble())
                scene.send(GreetingInput.Quiet)
            }
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) scene.send(GreetingInput.Tick(((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_TICK_SECONDS)))
                    last = now
                }
            }
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000f

/** How long the crew rests before one of them glances at a neighbour, plus up to [QUIET_JITTER] more. */
private val QUIET_SPELL = 6.seconds
private val QUIET_JITTER = 6.seconds

/** A frame longer than this (the window was hidden) counts as this long. */
private const val MAX_TICK_SECONDS = 0.1f
