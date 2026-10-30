package com.letta.mobile.ui.chat

import com.letta.mobile.data.chat.send.ServerQueueBlocker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class QueuedSendsLabelsTest {
    @Test
    fun serverQueueNoticeDoesNotClaimThePreviousResponseBelongsToAnotherDevice() {
        val label = queuedOnServerLabel(ServerQueueBlocker.OtherClient)
        assertFalse(label.contains("another client"))
        assertEquals("Queued on server · waiting for the previous response", label)
    }
}
