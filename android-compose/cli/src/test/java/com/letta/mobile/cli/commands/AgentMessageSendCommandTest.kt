package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.iroh.AgentSendResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * letta-mobile-bn008.4: the a2a-send CLI entry point's harness-facing JSON
 * contract. The messaging tool re-points to this command and parses the result;
 * the shape must be stable for delivered / unaddressable / failed.
 */
class AgentMessageSendCommandTest {

    @Test
    fun deliveredResultJsonContract() {
        val json = agentSendResultJson(AgentSendResult.Delivered("m-1"), "m-1")
        assertEquals("""{"ok":true,"delivered":true,"msgId":"m-1"}""", json)
    }

    @Test
    fun unaddressableResultJsonContract() {
        val json = agentSendResultJson(AgentSendResult.Unaddressable("agent-x", "not_registered"), "m-2")
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"error\":\"unaddressable\""))
        assertTrue(json.contains("\"toAgentId\":\"agent-x\""))
        assertTrue(json.contains("\"reason\":\"not_registered\""))
        assertTrue(json.contains("\"msgId\":\"m-2\""))
    }

    @Test
    fun failedResultJsonContract() {
        val json = agentSendResultJson(AgentSendResult.Failed("agent-y", "no_ack"), "m-3")
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"error\":\"failed\""))
        assertTrue(json.contains("\"reason\":\"no_ack\""))
    }

    @Test
    fun jsonEscapesSpecialCharactersInReason() {
        val json = agentSendResultJson(AgentSendResult.Failed("a", "he said \"hi\""), "m-4")
        assertTrue(json.contains("\\\"hi\\\""), "quotes in reason must be escaped: $json")
    }
}
