package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.ChatAutoScrollAction
import com.letta.mobile.feature.chat.screen.messagelist.ChatRestorationState
import com.letta.mobile.feature.chat.screen.messagelist.initialRestorationState
import com.letta.mobile.feature.chat.screen.messagelist.nextRestorationStateAfterViewport
import com.letta.mobile.feature.chat.screen.messagelist.restorationStateAfterUserInteraction
import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollActionTest {

    @Test
    fun `autoScrollAction skips within throttle window and snaps beyond it`() {
        assertAutoScrollExpectations(
            AutoScrollExpectation(
                ChatAutoScrollAction.Skip,
                ScrollTestAutoScrollCase(timing = ScrollTestAutoScrollTiming.throttled()),
            ),
            AutoScrollExpectation(
                ChatAutoScrollAction.Snap,
                ScrollTestAutoScrollCase(timing = ScrollTestAutoScrollTiming.readyToSnap()),
            ),
        )
    }

    @Test
    fun `autoScrollAction animates when user has scrolled up index or offset`() {
        assertAutoScrollExpectations(
            AutoScrollExpectation(
                ChatAutoScrollAction.Animate,
                ScrollTestAutoScrollCase(
                    viewport = ScrollTestLazyViewport.scrolledUpIndex(),
                    timing = ScrollTestAutoScrollTiming.readyToSnap(),
                ),
            ),
            AutoScrollExpectation(
                ChatAutoScrollAction.Animate,
                ScrollTestAutoScrollCase(
                    viewport = ScrollTestLazyViewport.scrolledUpOffset(),
                    timing = ScrollTestAutoScrollTiming.readyToSnap(),
                ),
            ),
        )
    }

    @Test
    fun `autoScrollAction animates for non-streaming or non-assistant roles`() {
        assertAutoScrollExpectations(
            AutoScrollExpectation(
                ChatAutoScrollAction.Animate,
                ScrollTestAutoScrollCase(
                    streaming = ScrollTestStreamingState.Settled,
                    timing = ScrollTestAutoScrollTiming.readyToSnap(),
                ),
            ),
            AutoScrollExpectation(
                ChatAutoScrollAction.Animate,
                ScrollTestAutoScrollCase(
                    signature = scrollTestSignature(ScrollTestSignatureSpec(role = "user", messageId = "m1")),
                    timing = ScrollTestAutoScrollTiming.readyToSnap(),
                ),
            ),
        )
    }

    @Test
    fun `streaming assistant auto-scroll follows snap-then-throttle timing policy`() {
        assertAutoScrollExpectations(
            AutoScrollExpectation(
                ChatAutoScrollAction.Snap,
                ScrollTestAutoScrollCase(
                    signature = streamingAssistantSignature(),
                    timing = ScrollTestAutoScrollTiming.streamingPinned(),
                ),
            ),
            AutoScrollExpectation(
                ChatAutoScrollAction.Skip,
                ScrollTestAutoScrollCase(
                    signature = streamingAssistantSignature(),
                    timing = ScrollTestAutoScrollTiming.streamingThrottled(),
                ),
            ),
        )
    }

    @Test
    fun `auto-scroll keeps animation for unpinned or non-streaming updates`() {
        assertAutoScrollExpectations(
            AutoScrollExpectation(
                ChatAutoScrollAction.Animate,
                ScrollTestAutoScrollCase(
                    signature = streamingAssistantSignature(),
                    viewport = ScrollTestLazyViewport.scrolledUpIndex(),
                    timing = ScrollTestAutoScrollTiming.streamingThrottled(),
                ),
            ),
            AutoScrollExpectation(
                ChatAutoScrollAction.Animate,
                ScrollTestAutoScrollCase(
                    signature = streamingAssistantSignature(),
                    streaming = ScrollTestStreamingState.Settled,
                    timing = ScrollTestAutoScrollTiming.streamingThrottled(),
                ),
            ),
        )
    }

    @Test
    fun `viewport restoration waits for both snapshot and first layout`() {
        assertEquals(ChatRestorationState.AwaitingSnapshot, initialRestorationState(hasItems = false))
        assertEquals(ChatRestorationState.AwaitingFirstLayout, initialRestorationState(hasItems = true))
        assertEquals(
            ChatRestorationState.AwaitingFirstLayout,
            nextRestorationStateAfterViewport(ChatRestorationState.AwaitingFirstLayout, followLatest = false),
        )
        assertEquals(ChatRestorationState.UserControlled, restorationStateAfterUserInteraction())
    }

    @Test
    fun `viewport restoration preserves user position until tail is followed again`() {
        assertEquals(
            ChatRestorationState.UserControlled,
            nextRestorationStateAfterViewport(ChatRestorationState.Restored, followLatest = false),
        )
        assertEquals(
            ChatRestorationState.UserControlled,
            nextRestorationStateAfterViewport(ChatRestorationState.UserControlled, followLatest = false),
        )
        assertEquals(
            ChatRestorationState.FollowTail,
            nextRestorationStateAfterViewport(ChatRestorationState.UserControlled, followLatest = true),
        )
    }
}

private fun streamingAssistantSignature() = newestMessageAutoScrollSignature(
    listOf(scrollTestMessage(ScrollTestMessageSpec(id = "assistant", content = "streaming"))),
)!!
