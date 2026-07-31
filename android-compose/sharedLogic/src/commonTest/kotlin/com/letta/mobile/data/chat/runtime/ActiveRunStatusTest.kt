package com.letta.mobile.data.chat.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class ActiveRunStatusTest {
    @Test
    fun errorOutranksEveryOtherState() {
        assertEquals(
            NowActiveStatus.Error,
            nowActiveStatus(isThinking = true, isStreaming = true, hasError = true),
        )
    }

    @Test
    fun thinkingOutranksStreaming() {
        assertEquals(
            NowActiveStatus.Thinking,
            nowActiveStatus(isThinking = true, isStreaming = true, hasError = false),
        )
    }

    @Test
    fun streamingWhenOnlyStreaming() {
        assertEquals(
            NowActiveStatus.Streaming,
            nowActiveStatus(isThinking = false, isStreaming = true, hasError = false),
        )
    }

    // letta-mobile-lgns8.19: an unconfirmed abort must read as "stopping",
    // never as ordinary work — the turn is still live until its terminal frame.
    @Test
    fun stoppingOutranksThinkingAndStreaming() {
        assertEquals(
            NowActiveStatus.Stopping,
            nowActiveStatus(
                isThinking = true,
                isStreaming = true,
                hasError = false,
                isStopping = true,
            ),
        )
    }

    @Test
    fun errorStillOutranksStopping() {
        assertEquals(
            NowActiveStatus.Error,
            nowActiveStatus(
                isThinking = false,
                isStreaming = true,
                hasError = true,
                isStopping = true,
            ),
        )
    }

    @Test
    fun idleWhenNothingIsHappening() {
        assertEquals(
            NowActiveStatus.Idle,
            nowActiveStatus(isThinking = false, isStreaming = false, hasError = false),
        )
    }
}
