package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-jt4wq: a user's own prompt must never be re-rendered as a
 * server "Error" bubble.
 *
 * Observed on device (dogfood of PR #1425): the user's dispatch message
 * — valid input that the agent went on to act upon — was painted as a red,
 * left-aligned card headed "Error", quoting their own words back at them.
 *
 * The mechanism was a conflation in this projection. `isError` documents itself
 * as "server-emitted error frame", and the Local branch here notes that
 * "Locals never originate as ERROR (server-only frame)" — so for a local user
 * message, `isError` could ONLY ever have come from the `deliveryState ==
 * FAILED` clause that used to be OR'd into it. Downstream,
 * `ChatBubbleStyle.bubbleStyle` treats `isError` as overriding role entirely,
 * replacing "You" with "Error" and the user's alignment/tint with the
 * destructive container.
 *
 * A send failure is local and retryable and belongs to the user's own bubble;
 * an error frame is the server reporting that the run went wrong. They are now
 * separate flags.
 */
class FailedUserSendIsNotAnErrorFrameTest {

    private fun localUserMessage(deliveryState: DeliveryState) = TimelineEvent.Local(
        position = 1.0,
        otid = "otid-user-1",
        content = "assign a sub agent to do a relatively mundane task",
        role = Role.USER,
        sentAt = parseTimelineInstant("2026-08-31T17:51:00Z"),
        deliveryState = deliveryState,
        messageType = TimelineMessageType.USER,
    )

    @Test
    fun `a failed user send is flagged as a send failure rather than an error frame`() {
        val ui = assertNotNull(timelineEventToUiMessage(localUserMessage(DeliveryState.FAILED)))

        assertEquals("user", ui.role)
        assertTrue(ui.isSendFailed, "a FAILED delivery should be reported as a send failure")
        assertFalse(
            ui.isError,
            "isError means a SERVER-emitted error frame; a local send failure is not one, " +
                "and setting it here repaints the user's own prompt as an Error bubble",
        )
    }

    @Test
    fun `the original words are preserved verbatim on a failed send`() {
        val ui = assertNotNull(timelineEventToUiMessage(localUserMessage(DeliveryState.FAILED)))
        assertEquals("assign a sub agent to do a relatively mundane task", ui.content)
    }

    @Test
    fun `a normal user send carries neither flag`() {
        val ui = assertNotNull(timelineEventToUiMessage(localUserMessage(DeliveryState.SENT)))
        assertFalse(ui.isError)
        assertFalse(ui.isSendFailed)
    }

    @Test
    fun `a sending user message is pending rather than failed`() {
        val ui = assertNotNull(timelineEventToUiMessage(localUserMessage(DeliveryState.SENDING)))
        assertTrue(ui.isPending)
        assertFalse(ui.isError)
        assertFalse(ui.isSendFailed)
    }
}
