package com.letta.mobile.data.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentMessageProvenanceProjectionTest {

    // ---- Inbound ----------------------------------------------------

    @Test
    fun `projectInbound builds provenance from a2a-encoded clientMessageId`() {
        val clientMessageId = AgentMessageClientId.encode(
            msgId = "msg-1",
            fromAgentId = "agent-meridian",
            toAgentId = "agent-pm",
        )
        val provenance = AgentMessageProvenanceProjection.projectInbound(
            clientMessageId = clientMessageId,
            ownAgentId = "agent-pm",
        )
        requireNotNull(provenance)
        assertEquals(AgentMessageDirection.INBOUND, provenance.direction)
        assertEquals("agent-meridian", provenance.fromAgentId)
        assertEquals("agent-pm", provenance.toAgentId)
        assertEquals("msg-1", provenance.msgId)
        assertEquals(AgentMessageDeliveryState.RECEIVER_CONFIRMED, provenance.deliveryState)
    }

    @Test
    fun `projectInbound falls back to the decoded toAgentId when ownAgentId is unknown`() {
        val clientMessageId = AgentMessageClientId.encode("msg-1", "agent-meridian", "agent-pm")
        val provenance = AgentMessageProvenanceProjection.projectInbound(
            clientMessageId = clientMessageId,
            ownAgentId = null,
        )
        assertEquals("agent-pm", provenance?.toAgentId)
    }

    @Test
    fun `projectInbound returns null for an ordinary human-sent message`() {
        // NO CONTENT HEURISTICS: a plain otid/UUID clientMessageId (the
        // normal shape for a human-typed message) must never be treated as
        // agent provenance, no matter what.
        val provenance = AgentMessageProvenanceProjection.projectInbound(
            clientMessageId = "3f9a9e2e-1b3e-4b3e-9c3e-1b3e4b3e9c3e",
            ownAgentId = "agent-pm",
        )
        assertNull(provenance)
    }

    @Test
    fun `projectInbound returns null when clientMessageId is null`() {
        assertNull(AgentMessageProvenanceProjection.projectInbound(null, "agent-pm"))
    }

    // ---- Outbound -----------------------------------------------------

    private fun deliveredResult(msgId: String, to: String) =
        """{"ok":true,"delivered":true,"msgId":"$msgId","to":"$to"}"""

    private fun acceptedResult(msgId: String, to: String) =
        """{"ok":true,"accepted":true,"delivered":false,"msgId":"$msgId","to":"$to"}"""

    @Test
    fun `projectOutbound returns null for a tool call that is not agent_message_send`() {
        val provenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = "some_other_tool",
            argumentsJson = """{"to":"agent-b","body":"hi"}""",
            resultJson = deliveredResult("msg-1", "agent-b"),
            isError = false,
            fromAgentId = "agent-a",
        )
        assertNull(provenance)
    }

    @Test
    fun `projectOutbound returns null when fromAgentId is unknown`() {
        val provenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = "agent_message_send",
            argumentsJson = """{"to":"agent-b","body":"hi"}""",
            resultJson = null,
            isError = false,
            fromAgentId = null,
        )
        assertNull(provenance)
    }

    @Test
    fun `projectOutbound returns null when the 'to' argument is missing`() {
        val provenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = "agent_message_send",
            argumentsJson = """{"body":"hi"}""",
            resultJson = null,
            isError = false,
            fromAgentId = "agent-a",
        )
        assertNull(provenance)
    }

    @Test
    fun `projectOutbound is PENDING while the tool call has no result yet`() {
        val provenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = "agent_message_send",
            argumentsJson = """{"to":"agent-b","body":"hi"}""",
            resultJson = null,
            isError = false,
            fromAgentId = "agent-a",
        )
        requireNotNull(provenance)
        assertEquals(AgentMessageDirection.OUTBOUND, provenance.direction)
        assertEquals("agent-a", provenance.fromAgentId)
        assertEquals("agent-b", provenance.toAgentId)
        assertEquals(AgentMessageDeliveryState.PENDING, provenance.deliveryState)
    }

    @Test
    fun `projectOutbound is SENT when transport accepted but application delivery unconfirmed`() {
        val provenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = "agent_message_send",
            argumentsJson = """{"to":"agent-b","body":"hi"}""",
            resultJson = acceptedResult("msg-1", "agent-b"),
            isError = false,
            fromAgentId = "agent-a",
        )
        assertEquals(AgentMessageDeliveryState.SENT, provenance?.deliveryState)
        assertEquals("msg-1", provenance?.msgId)
    }

    @Test
    fun `projectOutbound is RECEIVER_CONFIRMED when delivered=true`() {
        val provenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = "agent_message_send",
            argumentsJson = """{"to":"agent-b","body":"hi"}""",
            resultJson = deliveredResult("msg-1", "agent-b"),
            isError = false,
            fromAgentId = "agent-a",
        )
        assertEquals(AgentMessageDeliveryState.RECEIVER_CONFIRMED, provenance?.deliveryState)
    }

    @Test
    fun `projectOutbound is FAILED with the tool's typed error text on isError`() {
        val provenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = "agent_message_send",
            argumentsJson = """{"to":"agent-b","body":"hi"}""",
            resultJson = "agent_message_send: target 'agent-b' is unaddressable: no_address",
            isError = true,
            fromAgentId = "agent-a",
        )
        requireNotNull(provenance)
        assertEquals(AgentMessageDeliveryState.FAILED, provenance.deliveryState)
        assertEquals(
            "agent_message_send: target 'agent-b' is unaddressable: no_address",
            provenance.failureReason,
        )
    }
}
