package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.UsageError
import com.letta.mobile.data.transport.iroh.AgentSendResult
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
        assertEquals("""{"ok":true,"accepted":true,"applicationDelivered":true,"msgId":"m-1"}""", json)
    }

    @Test
    fun acceptedButUndeliveredResultJsonContract() {
        val json = agentSendResultJson(AgentSendResult.Accepted("m-accepted", "agent-x"), "m-accepted")
        assertEquals(
            """{"ok":true,"accepted":true,"applicationDelivered":false,"msgId":"m-accepted","toAgentId":"agent-x"}""",
            json,
        )
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

/**
 * letta-mobile-e12nf: pins the fix for the gap where [DefaultIrohCliRunner]
 * (in sharedLogic) has ALWAYS invoked this command with `--body-file -`, but
 * this command previously only had a `--body` option — so every real
 * `agent_message_send` tool call would have failed with an unrecognized-flag
 * error before this fix landed. These tests exercise [resolveBody] directly
 * (the pure function [AgentMessageSendCommand.run] delegates to) rather than
 * spawning the full clikt command, so they stay hermetic and fast.
 */
class AgentMessageSendCommandBodyResolutionTest {

    @Test
    fun inlineBodyIsUsedVerbatim() {
        assertEquals("hello world", resolveBody("hello world", null))
    }

    @Test
    fun bodyFileDashReadsFromStdin() {
        val original = System.`in`
        try {
            // Multi-line + embedded quote + ampersand, matching the shape
            // DefaultIrohCliRunnerTest's multiLineBodyRoundTripsViaStdin
            // pins on the sender side — this is the RECEIVING half of the
            // same contract.
            val body = "line one\nline two with \"quotes\" & an URL https://example.com/x?y=1\nline three"
            System.setIn(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
            val resolved = resolveBody(null, "-")
            assertEquals(body, resolved, "stdin body must round-trip exactly, no collapse")
        } finally {
            System.setIn(original)
        }
    }

    @Test
    fun bodyFilePathReadsFileContents() {
        val tmp = Files.createTempFile("agent-message-body", ".txt")
        try {
            Files.write(tmp, "body from a real file\nwith a second line".toByteArray(Charsets.UTF_8))
            assertEquals(
                "body from a real file\nwith a second line",
                resolveBody(null, tmp.toAbsolutePath().toString()),
            )
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun bothBodyAndBodyFileIsRejected() {
        val error = assertThrows(UsageError::class.java) {
            resolveBody("inline", "-")
        }
        assertTrue(
            error.message.orEmpty().contains("only one"),
            "error must explain the conflict, got: ${error.message}",
        )
    }

    @Test
    fun neitherBodyNorBodyFileIsRejected() {
        val error = assertThrows(UsageError::class.java) {
            resolveBody(null, null)
        }
        assertTrue(
            error.message.orEmpty().contains("--body"),
            "error must name the missing options, got: ${error.message}",
        )
    }

    @Test
    fun missingBodyFilePathIsRejectedWithClearError() {
        val error = assertThrows(UsageError::class.java) {
            resolveBody(null, "/nonexistent/path/${System.nanoTime()}")
        }
        assertTrue(
            error.message.orEmpty().contains("--body-file"),
            "error must name the offending flag, got: ${error.message}",
        )
    }
}
