package com.letta.mobile.feature.chat.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAutoScrollActionTest {

    private fun makeSignature(role: String, messageId: String = "m1"): ChatAutoScrollSignature =
        ChatAutoScrollSignature(
            messageId = messageId, role = role, contentLength = 0, contentHash = 0,
            latencyMs = null, toolCallsHash = 0, generatedUiHash = 0, approvalHash = 0, attachmentCount = 0
        )

    @Test
    fun `autoScrollAction animates for non-streaming messages`() {
        val signature = makeSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L

        val action = autoScrollAction(
            signature = signature,
            isStreaming = false, // Not streaming
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs
        )

        assertEquals(ChatAutoScrollAction.Animate, action)
    }

    @Test
    fun `autoScrollAction animates for non-assistant roles`() {
        val signature = makeSignature(role = "user", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L

        val action = autoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs
        )

        assertEquals(ChatAutoScrollAction.Animate, action)
    }

    @Test
    fun `autoScrollAction skips rapid streaming frames within throttle limit`() {
        val signature = makeSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 50L // 50ms < 96ms throttle

        val action = autoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs
        )

        assertEquals(ChatAutoScrollAction.Skip, action)
    }

    @Test
    fun `autoScrollAction snaps for streaming frames beyond throttle limit`() {
        val signature = makeSignature(role = "assistant", messageId = "m1")
        val nowMs = 1000L
        val lastStreamingSnapMs = nowMs - 100L // 100ms > 96ms throttle

        val action = autoScrollAction(
            signature = signature,
            isStreaming = true,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            lastStreamingSnapMs = lastStreamingSnapMs,
            nowMs = nowMs
        )

        assertEquals(ChatAutoScrollAction.Snap, action)
    }
}
