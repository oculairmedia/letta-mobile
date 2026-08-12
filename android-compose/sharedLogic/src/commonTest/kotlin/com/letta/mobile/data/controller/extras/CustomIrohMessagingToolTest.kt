package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): the agent-visible Iroh
 * messaging tool — the new `agent_message_send` that lets agents message each
 * other over Iroh, distinct from `matrix_agent_message` which is the
 * agent↔human (Matrix / Messenger) surface.
 *
 * These tests pin the tool's behaviour end-to-end (input parsing → runner
 * invocation → result mapping). The runner is stubbed so the suite stays
 * hermetic; the runner itself has its own tests in [DefaultIrohCliRunnerTest].
 */
class CustomIrohMessagingToolTest {

    /**
     * The regression test pinned from bn008-phase2-handoff risk #1:
     * Meridian→Lester sent a multi-line body and it collapsed to "" because
     * shell quoting + `tr '\n' ' '` mangled it. This test exercises the FULL
     * tool surface with a multi-line body (newlines, quotes, ampersands, a
     * URL) and asserts the runner receives every byte verbatim.
     */
    @Test
    fun multiLineBodyRoundTripsViaStdin() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true", // ignored — runner is stubbed
            runner = captured,
        )

        val multiline = """
            Status update from PM:
              - closed 3 beads
              - opened 1 bead
              - see https://example.com/path?x=1&y=2

            No action needed.
        """.trimIndent() + "\n"

        val input = buildJsonObject {
            put("to", "agent-target")
            put("body", multiline)
        }

        val result = tool.invoke(input, agentId = "agent-sender")
        assertIs<ExternalToolResult.Success>(result, "delivered must surface as Success")

        // The captured stdin bytes must equal the input body EXACTLY — every
        // newline, every quote, every ampersand, the trailing newline.
        assertEquals(1, captured.calls.size, "runner must be called exactly once")
        val call = captured.calls.single()
        assertEquals(multiline, call.body, "multi-line body must round-trip unchanged via stdin")
        assertEquals("agent-sender", call.fromAgentId, "--from must come from the dispatcher-provided agentId")
        assertEquals("agent-target", call.toAgentId)
    }

    @Test
    fun invokesRunnerWithBinaryIdentityAndAddressBook() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/opt/custom/meridian",
            identityDir = "/custom/identities",
            addressStore = "/custom/addresses.kv",
            runner = captured,
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "x"); put("body", "y") },
            agentId = "from-x",
        )
        assertIs<ExternalToolResult.Success>(result)
        val call = captured.calls.single()
        assertEquals("/opt/custom/meridian", call.binary)
        assertEquals("/custom/identities", call.identityDir)
        assertEquals("/custom/addresses.kv", call.addressStore)
    }

    @Test
    fun runnerDeliveredMapsToStructuredSuccess() = runTest {
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = FixedRunner(IrohCliSendResult.Delivered("msg-fixed-1")),
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "tgt"); put("body", "hi") },
            agentId = "src",
        )
        val success = assertIs<ExternalToolResult.Success>(result)
        // The success payload must carry the msgId back so the agent's log
        // shows the wire identity, not just "ok:true".
        assertTrue(
            success.content.contains("\"msgId\":\"msg-fixed-1\""),
            "success content must include the runner's msgId, got: ${success.content}",
        )
        assertTrue(success.content.contains("\"to\":\"tgt\""))
        assertTrue(success.content.contains("\"delivered\":true"))
    }

    @Test
    fun runnerUnaddressableMapsToDescriptiveError() = runTest {
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = FixedRunner(IrohCliSendResult.Unaddressable("tgt", "no_kv_row")),
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "tgt"); put("body", "hi") },
            agentId = "src",
        )
        val err = assertIs<ExternalToolResult.Error>(result)
        assertTrue(err.error.contains("unaddressable"))
        assertTrue(err.error.contains("no_kv_row"))
        assertTrue(err.error.contains("tgt"))
    }

    @Test
    fun runnerFailedMapsToDescriptiveError() = runTest {
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = FixedRunner(IrohCliSendResult.Failed("tgt", "no_ack")),
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "tgt"); put("body", "hi") },
            agentId = "src",
        )
        val err = assertIs<ExternalToolResult.Error>(result)
        assertTrue(err.error.contains("failed"))
        assertTrue(err.error.contains("no_ack"))
    }

    @Test
    fun missingAgentIdSurfacesStructuredError() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = captured,
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "tgt"); put("body", "hi") },
            agentId = null, // The dispatcher's request.runtime was unset
        )
        val err = assertIs<ExternalToolResult.Error>(result)
        assertTrue(
            err.error.contains("agentId"),
            "error must mention the missing agentId context, got: ${err.error}",
        )
        assertEquals(0, captured.calls.size, "runner must NOT be invoked when fromAgentId is unknown")
    }

    @Test
    fun missingToFieldSurfacesStructuredError() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = captured,
        )
        val result = tool.invoke(
            input = buildJsonObject { put("body", "hi") }, // 'to' missing
            agentId = "src",
        )
        val err = assertIs<ExternalToolResult.Error>(result)
        assertTrue(
            err.error.contains("'to'"),
            "error must name the missing field, got: ${err.error}",
        )
        assertEquals(0, captured.calls.size, "runner must NOT be invoked when input is invalid")
    }

    @Test
    fun missingBodyFieldSurfacesStructuredError() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = captured,
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "tgt") }, // 'body' missing
            agentId = "src",
        )
        val err = assertIs<ExternalToolResult.Error>(result)
        assertTrue(err.error.contains("'body'"))
        assertEquals(0, captured.calls.size, "runner must NOT be invoked when input is invalid")
    }

    @Test
    fun blankToFieldRejected() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = captured,
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "   "); put("body", "hi") },
            agentId = "src",
        )
        val err = assertIs<ExternalToolResult.Error>(result)
        assertTrue(err.error.contains("blank"))
        assertEquals(0, captured.calls.size)
    }

    /**
     * Self-echo guard (letta-mobile-hj69d sibling): the receiver's a2a-recv
     * handler also filters this, but failing fast here saves a round trip
     * and surfaces a clearer error to the agent.
     */
    @Test
    fun selfSendRejected() = runTest {
        val captured = CapturingRunner()
        val tool = CustomIrohMessagingTool(
            binary = "/bin/true",
            runner = captured,
        )
        val result = tool.invoke(
            input = buildJsonObject { put("to", "self"); put("body", "hi") },
            agentId = "self",
        )
        val err = assertIs<ExternalToolResult.Error>(result)
        assertTrue(err.error.contains("self"), "error must reference self-echo, got: ${err.error}")
        assertEquals(0, captured.calls.size, "runner must NOT be invoked for self-send")
    }

    @Test
    fun toolMetadataAdvertised() {
        val tool = CustomIrohMessagingTool(binary = "/bin/true", runner = CapturingRunner())
        assertEquals("agent_message_send", tool.name)
        assertEquals(Capability.AgentMessaging, tool.capability)
        assertTrue(
            tool.description.contains("Iroh"),
            "description should make the agent↔agent Iroh surface obvious to the model",
        )
        assertTrue(
            tool.description.contains("matrix_agent_message"),
            "description should cross-reference matrix_agent_message so the model " +
                "picks the right tool for each relationship (agent↔agent vs agent�human)",
        )
        // Schema sanity: both fields are required (the dispatcher enforces
        // this server-side too, but a schema-only contract is what the
        // App Server validates the tool list against).
        val required = tool.inputSchema!!.requiredFields()
        assertTrue("to" in required, "'to' must be required")
        assertTrue("body" in required, "'body' must be required")
    }

    // === test helpers ===

    /**
     * Records every (binary, fromAgentId, toAgentId, body, identityDir,
     * addressStore) tuple passed to [send], so tests can assert exactly
     * what reached the runner without spawning a process.
     */
    private class CapturingRunner : IrohCliRunner {
        data class Call(
            val binary: String,
            val fromAgentId: String,
            val toAgentId: String,
            val body: String,
            val identityDir: String?,
            val addressStore: String?,
        )
        val calls: MutableList<Call> = mutableListOf()
        override suspend fun send(
            binary: String,
            fromAgentId: String,
            toAgentId: String,
            body: String,
            identityDir: String?,
            addressStore: String?,
        ): IrohCliSendResult {
            calls += Call(binary, fromAgentId, toAgentId, body, identityDir, addressStore)
            return IrohCliSendResult.Delivered("captured-${calls.size}")
        }
    }

    /** Always returns the same result — for the mapping tests. */
    private class FixedRunner(private val result: IrohCliSendResult) : IrohCliRunner {
        override suspend fun send(
            binary: String,
            fromAgentId: String,
            toAgentId: String,
            body: String,
            identityDir: String?,
            addressStore: String?,
        ): IrohCliSendResult = result
    }
}

/**
 * Read the JSON-Schema `required` array out of a tool's input schema. Helper
 * for the metadata test above; lifted to top level so future tests can reuse.
 */
internal fun JsonObject.requiredFields(): Set<String> {
    val required = this["required"] ?: return emptySet()
    val arr = required as? JsonArray ?: return emptySet()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
}
