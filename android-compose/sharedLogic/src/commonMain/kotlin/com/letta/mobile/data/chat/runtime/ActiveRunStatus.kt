package com.letta.mobile.data.chat.runtime

/** Coarse status of the active conversation for now-playing style surfaces. */
enum class NowActiveStatus { Idle, Thinking, Streaming, Stopping, Error }

/**
 * Precedence reducer: error > stopping > thinking > streaming > idle.
 *
 * letta-mobile-lgns8.19: [isStopping] outranks thinking/streaming so a run with
 * an unconfirmed abort in flight reads as "stopping…" rather than as ordinary
 * work — the turn is still live server-side until its terminal frame lands.
 */
fun nowActiveStatus(
    isThinking: Boolean,
    isStreaming: Boolean,
    hasError: Boolean,
    isStopping: Boolean = false,
): NowActiveStatus = when {
    hasError -> NowActiveStatus.Error
    isStopping -> NowActiveStatus.Stopping
    isThinking -> NowActiveStatus.Thinking
    isStreaming -> NowActiveStatus.Streaming
    else -> NowActiveStatus.Idle
}
