package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.ChatAutoScrollAction
import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
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
}

private fun streamingAssistantSignature() = newestMessageAutoScrollSignature(
    listOf(scrollTestMessage(ScrollTestMessageSpec(id = "assistant", content = "streaming"))),
)!!
