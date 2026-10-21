package com.letta.mobile.data.chat.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversationTurnStateAcceptanceTest {
    @Test
    fun acceptedPublicationSeesMatchingIdentityAndSend() {
        val state = ConversationTurnState("conversation")
        val pending = QueuedChatSend(QueuedSendId("accepted"), QueueConversationId("conversation"), "hello")
        state.reachedTerminal = true
        var published = false
        assertTrue(state.acceptSend(pending, send = {
            assertEquals(null, state.identity.active)
            assertEquals(null, state.inFlightSend)
            true
        }, onAccepted = {
            assertNotNull(state.identity.active)
            assertEquals("conversation", state.identity.active?.conversationId)
            assertEquals("accepted", state.otid)
            assertSame(pending, state.inFlightSend)
            assertEquals("conversation", state.localConversationId)
            assertFalse(state.reachedTerminal)
            published = true
        }))
        assertTrue(published)
    }

    @Test
    fun rejectedSendCannotReplacePriorIdentityOrPendingSend() {
        val state = ConversationTurnState("conversation")
        val prior = QueuedChatSend(QueuedSendId("prior"), QueueConversationId("conversation"), "prior")
        assertTrue(state.acceptSend(prior, { true }, {}))
        val identity = state.identity.active
        val rejected = prior.copy(id = QueuedSendId("rejected"))
        assertFalse(state.acceptSend(rejected, { false }, { error("rejected send published") }))
        assertSame(identity, state.identity.active)
        assertSame(prior, state.inFlightSend)
        assertEquals("prior", state.otid)
    }
}
