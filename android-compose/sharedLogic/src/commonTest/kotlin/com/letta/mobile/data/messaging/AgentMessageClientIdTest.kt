package com.letta.mobile.data.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentMessageClientIdTest {

    @Test
    fun `encode then decode round-trips msgId and agent ids`() {
        val encoded = AgentMessageClientId.encode(
            msgId = "msg-abc-123",
            fromAgentId = "agent-meridian",
            toAgentId = "agent-pm-letta-mobile",
        )
        val decoded = AgentMessageClientId.decode(encoded)
        assertEquals("msg-abc-123", decoded?.msgId)
        assertEquals("agent-meridian", decoded?.fromAgentId)
        assertEquals("agent-pm-letta-mobile", decoded?.toAgentId)
    }

    @Test
    fun `decode returns null for a plain non-a2a clientMessageId`() {
        // Ordinary human-sent messages use a bare otid/UUID clientMessageId —
        // this must NEVER be misread as agent provenance.
        assertNull(AgentMessageClientId.decode("3f9a9e2e-1b3e-4b3e-9c3e-1b3e4b3e9c3e"))
    }

    @Test
    fun `decode returns null for null input`() {
        assertNull(AgentMessageClientId.decode(null))
    }

    @Test
    fun `decode returns null for malformed a2a-prefixed id`() {
        assertNull(AgentMessageClientId.decode("a2a:v1:only-one-part"))
        assertNull(AgentMessageClientId.decode("a2a:v1:msg-1::to-agent"))
        assertNull(AgentMessageClientId.decode("a2a:v1:msg-1:from-agent:"))
    }

    @Test
    fun `encode falls back to plain msgId when a component contains the delimiter`() {
        val encoded = AgentMessageClientId.encode(
            msgId = "msg-1",
            fromAgentId = "agent:with:colon",
            toAgentId = "agent-b",
        )
        assertEquals("msg-1", encoded)
        assertNull(AgentMessageClientId.decode(encoded))
    }

    @Test
    fun `dedup identity normalizes encoded and legacy ids`() {
        val encoded = AgentMessageClientId.encode("msg-1", "agent-a", "agent-b")

        assertEquals("msg-1", AgentMessageClientId.dedupIdentity(encoded))
        assertEquals("msg-1", AgentMessageClientId.dedupIdentity("msg-1"))
    }
}
