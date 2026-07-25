package com.letta.mobile.data.chat.runtime

/** Coarse status of the active conversation for now-playing style surfaces. */
enum class NowActiveStatus { Idle, Thinking, Streaming, Error }

/** Precedence reducer: error > thinking > streaming > idle. */
fun nowActiveStatus(
    isThinking: Boolean,
    isStreaming: Boolean,
    hasError: Boolean,
): NowActiveStatus = when {
    hasError -> NowActiveStatus.Error
    isThinking -> NowActiveStatus.Thinking
    isStreaming -> NowActiveStatus.Streaming
    else -> NowActiveStatus.Idle
}
