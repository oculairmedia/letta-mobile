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

    @Test
    fun idleWhenNothingIsHappening() {
        assertEquals(
            NowActiveStatus.Idle,
            nowActiveStatus(isThinking = false, isStreaming = false, hasError = false),
        )
    }
}
