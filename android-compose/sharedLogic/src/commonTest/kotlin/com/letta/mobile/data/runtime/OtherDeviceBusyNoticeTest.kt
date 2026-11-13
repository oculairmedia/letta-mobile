package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-6vf4r (S4): the host's same-conversation busy rejection is its own failure family.
 *
 * The text is the one [BusyOwnerReconciler] throws when a second device sends into a conversation
 * whose turn another device runs. It used to classify as `other`, so the sender read "This turn
 * failed before the assistant could reply." for a message that was never sent at all.
 */
class OtherDeviceBusyNoticeTest {
    @Test
    fun hostBusyRejectionTextIsItsOwnFamily() {
        assertEquals("other_device_busy", terminalReasonKind(HOST_BUSY_TEXT))
        assertEquals("other_device_busy", terminalReasonKind("Iroh App Server turn engine is already busy."))
    }

    @Test
    fun aConversationBusyWithARunKeepsItsOwnFamily() {
        assertEquals("conversation_busy", terminalReasonKind("Conversation conv-1 is busy with run local-run-7"))
    }

    @Test
    fun theNoticeSaysTheMessageWasNotSent() {
        val notice = TurnFailureNotices.forFailedTerminal(HOST_BUSY_TEXT, deliveredAssistantContent = false)
        assertEquals("other_device_busy", notice?.kind)
        assertEquals(NOT_SENT_COPY, notice?.message)
        assertNotEquals(TurnFailureNotices.GENERIC_MESSAGE, notice?.message)
    }

    @Test
    fun theFamilyIsTrustedFromTheWireAndRecoveredFromItsCopy() {
        assertTrue(TurnFailureNotices.isKnownKind("other_device_busy"))
        assertEquals("other_device_busy", TurnFailureNotices.kindForMessage(NOT_SENT_COPY))
        // The other device's reply may already be streaming into this timeline: that is not this
        // message's delivery, so the notice must not turn generic.
        val notice = TurnFailureNotices.forFailedTerminal(
            reason = HOST_BUSY_TEXT,
            deliveredAssistantContent = true,
            kindHint = "other_device_busy",
        )
        assertEquals(NOT_SENT_COPY, notice?.message)
    }

    private companion object {
        const val HOST_BUSY_TEXT = "An App Server turn is already active for agent-1/conv-1."
        const val NOT_SENT_COPY =
            "Another device is running a turn in this conversation; your message was not sent."
    }
}
