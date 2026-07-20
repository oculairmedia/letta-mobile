package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.ChatAutoScrollAction
import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollActionTest {

    @Test
    fun `autoScrollAction skips rapid streaming frames within throttle limit`() {
        val signature = scrollTestSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 50L

        val action = scrollTestAutoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs,
        )

        assertEquals(ChatAutoScrollAction.Skip, action)
    }

    @Test
    fun `autoScrollAction snaps for streaming frames beyond throttle limit`() {
        val signature = scrollTestSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L

        val action = scrollTestAutoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs,
        )

        assertEquals(ChatAutoScrollAction.Snap, action)
    }

    @Test
    fun `autoScrollAction animates when user has scrolled up index`() {
        val signature = scrollTestSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L

        val action = scrollTestAutoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs,
        )

        assertEquals(ChatAutoScrollAction.Animate, action)
    }

    @Test
    fun `autoScrollAction animates when user has scrolled up offset`() {
        val signature = scrollTestSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L

        val action = scrollTestAutoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 13,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs,
        )

        assertEquals(ChatAutoScrollAction.Animate, action)
    }

    @Test
    fun `autoScrollAction animates for non-streaming messages`() {
        val signature = scrollTestSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L

        val action = scrollTestAutoScrollAction(
            signature = signature,
            isStreaming = false,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs,
        )

        assertEquals(ChatAutoScrollAction.Animate, action)
    }

    @Test
    fun `autoScrollAction animates for non-assistant roles`() {
        val signature = scrollTestSignature(role = "user", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L

        val action = scrollTestAutoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs,
        )

        assertEquals(ChatAutoScrollAction.Animate, action)
    }

    @Test
    fun `streaming assistant auto-scroll snaps when already pinned`() {
        val signature = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(id = "assistant", content = "streaming")),
        )!!

        assertEquals(
            ChatAutoScrollAction.Snap,
            scrollTestAutoScrollAction(
                signature = signature,
                isStreaming = true,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                lastStreamingSnapMs = 0L,
                nowMs = 120L,
            ),
        )
    }

    @Test
    fun `streaming assistant auto-scroll throttles repeated pinned snaps`() {
        val signature = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(id = "assistant", content = "streaming")),
        )!!

        assertEquals(
            ChatAutoScrollAction.Skip,
            scrollTestAutoScrollAction(
                signature = signature,
                isStreaming = true,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                lastStreamingSnapMs = 100L,
                nowMs = 150L,
            ),
        )
    }

    @Test
    fun `auto-scroll keeps animation for unpinned or non-streaming updates`() {
        val signature = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(id = "assistant", content = "streaming")),
        )!!

        assertEquals(
            ChatAutoScrollAction.Animate,
            scrollTestAutoScrollAction(
                signature = signature,
                isStreaming = true,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
                lastStreamingSnapMs = 100L,
                nowMs = 150L,
            ),
        )
        assertEquals(
            ChatAutoScrollAction.Animate,
            scrollTestAutoScrollAction(
                signature = signature,
                isStreaming = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                lastStreamingSnapMs = 100L,
                nowMs = 150L,
            ),
        )
    }
}
