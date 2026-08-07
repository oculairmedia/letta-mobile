package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-bn008.6: headless unit tests for the a2a (direct agent-to-agent)
 * wiring helper. Validates the contract the live [AppServerServeIrohCommand]
 * depends on without bringing up a full controller / app-server.
 *
 * The live iroh-ffi loopback tests (`runIrohLiveE2E=true`) cover end-to-end
 * envelope delivery in `sharedLogic`; this layer stays JVM-only and tests:
 *  - the build returns an [A2aWiring] with a non-blank node id,
 *  - the receiver/router references are wired (same router instance the wiring
 *    received, accept-loop job is reachable),
 *  - the publish path writes per-agent entries into the kv store,
 *  - the helper refuses to bind with neither a non-empty publishAgents list
 *    nor an existing address book.
 *
 * Native bind (which talks QUIC) requires the iroh-ffi jar + a usable port.
 * Gated by `runIrohNativeE2E=true` so the default `:iroh-wrapper-cli:test`
 * gate stays hermetic.
 */
class A2aWiringTest {
    private fun nativeEnabled(): Boolean =
        System.getProperty("runIrohNativeE2E") == "true"

    @Test
    fun `build returns an A2aWiring with non-blank hex node id and a wired receiver`() {
        assumeTrue(nativeEnabled(), "set -DrunIrohNativeE2E=true to run the loopback a2a build probe")
        val tmp = Files.createTempDirectory("bn008-6-wire").toFile()
        try {
            val addressBook = File(tmp, "agents.kv").also { it.createNewFile() }
            val identitiesDir = File(tmp, "identities")
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = identitiesDir,
                addressBook = addressBook,
                publishAgents = listOf(),
            )
            val wiring = runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) }
            try {
                assertNotNull(wiring.endpoint, "endpoint must be bound")
                assertEquals(64, wiring.nodeIdHex.length, "nodeIdHex must be 64 hex chars")
                // N4 (PR #1125): replace the previous self-compare tautologies
                // (`assertEquals(wiring.router, wiring.router)` and
                // `assertEquals(wiring.router::class, wiring.router::class)`,
                // both trivially true) with assertions that pin the contract:
                //   - the receiver is wired (not null and not a placeholder),
                //   - the cached node id is a valid lowercase hex string
                //     (stored at bind time — no per-read `runBlocking`).
                assertNotNull(wiring.receiver, "receiver must be wired")
                assertTrue(wiring.nodeIdHex.all { it in "0123456789abcdef" }, "nodeIdHex must be hex")
            } finally {
                wiring.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `publish writes the per-agent entry into the kv store`() {
        assumeTrue(nativeEnabled(), "set -DrunIrohNativeE2E=true to run the loopback a2a build probe")
        val tmp = Files.createTempDirectory("bn008-6-wire").toFile()
        try {
            val addressBook = File(tmp, "agents.kv").also { it.createNewFile() }
            val identitiesDir = File(tmp, "identities")
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = identitiesDir,
                addressBook = addressBook,
                publishAgents = listOf("Meridian", "PM-letta-mobile"),
            )
            val wiring = runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) }
            try {
                val published = runBlocking { publishLocalAgents(cfg, wiring.endpoint) }
                assertEquals(listOf("Meridian", "PM-letta-mobile"), published)
                val content = addressBook.readText()
                assertTrue("Meridian" in content, "Meridian missing from kv: $content")
                assertTrue("PM-letta-mobile" in content, "PM-letta-mobile missing from kv: $content")
                assertTrue(wiring.nodeIdHex in content, "node id missing from kv: $content")
            } finally {
                wiring.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `build refuses empty publishAgents and missing addressBook`() {
        // Pure-Kotlin guard — does NOT touch iroh, so it runs without opt-in.
        val tmp = Files.createTempDirectory("bn008-6-wire").toFile()
        try {
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = File(tmp, "identities"),
                addressBook = File(tmp, "does-not-exist.kv"),
                publishAgents = emptyList(),
            )
            val ex = runCatching { runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) } }
                .exceptionOrNull()
            assertNotNull(ex, "build must refuse empty publishAgents + missing addressBook")
            assertTrue(
                ex!!.message?.contains("nothing to bind") == true ||
                    ex.message?.contains("publishAgents is empty") == true,
                "unexpected error: ${ex.message}",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // N5 (PR #1125): hermetic default-gate coverage for the routing logic
    // that the receiver calls on every inbound message. These do NOT bring
    // up the iroh native binding — they pin the decoded-row shape, the busy
    // resolution, and the Deliver path's client.input call against the
    // exact production call sites in A2aWiring.kt.
    //
    // Fixtures are written inline because LocalBackendFixtureStore lives in
    // sharedLogic's jvmTest source set (not exported as a test fixture), so
    // it is NOT on this module's test classpath. We only need the rows the
    // receiver reads; the readers are best-effort and skip missing dirs.
    // ---------------------------------------------------------------------

    private fun writeConversation(base: File, key: String, json: String) {
        val dir = File(base, "conversations/${b64u(key)}").apply { mkdirs() }
        File(dir, "conversation.json").writeText(json)
    }

    private fun writeRun(base: File, runId: String, agentId: String, conversationId: String, status: String, archived: Boolean = false) {
        val dir = if (archived) File(base, "runs/_archive/$runId") else File(base, "runs/$runId")
        dir.mkdirs()
        File(dir, "run.json").writeText(
            """{"id":"$runId","agent_id":"$agentId","conversation_id":"$conversationId","status":"$status","background":false,"stop_reason":"end_turn","created_at":"2026-07-22T20:00:00.000Z","message_ids":[],"num_steps":0}""",
        )
    }

    private fun b64u(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test
    fun `listConversationsForAgent returns Interactive conversations for an agent with active runs`() {
        // Hermetic: no iroh endpoint; calls the internal helper directly.
        val base = Files.createTempDirectory("bn008-6-listconvs").toFile()
        try {
            val agentId = "agent-1"
            // Two conversations, both Interactive (the helper's
            // `archiveStatus = "active"` filter is what pins this).
            writeConversation(base, "default:$agentId", """{"id":"conv-default","agent_id":"$agentId","last_message_at":"2026-07-22T20:05:00.000Z"}""")
            writeConversation(base, "conversation:conv-other", """{"id":"conv-other","agent_id":"$agentId","last_message_at":"2026-07-22T20:01:00.000Z"}""")
            // One running run on conv-other -> busy=true there.
            writeRun(base, "run-busy", agentId, conversationId = "conv-other", status = "running")
            // A completed run on the default conversation -> NOT busy.
            writeRun(base, "run-done", agentId, conversationId = "conv-default", status = "completed")

            val store = LocalBackendAdminStore(base)
            val states = runBlocking { listConversationsForAgent(store, agentId) }

            // Both conversations present, sorted newest-first.
            assertEquals(2, states.size, "expected 2 conversations")
            assertEquals("conv-default", states[0].conversation.id.value)
            assertEquals("conv-other", states[1].conversation.id.value)
            // busy flag derived from activeConversationIds (per N1 doc note:
            // limit caps the page, not the read; for 2 runs this is exact).
            assertEquals(setOf("conv-other"), states.filter { it.busy }.map { it.conversation.id.value }.toSet())
            // The decoder pins class to Interactive by default.
            states.forEach { assertSame(ConversationClass.INTERACTIVE, it.conversation.effectiveClass) }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `decodeConversation extracts id, agent_id, and conversation_class from a real kv row`() {
        // Hermetic: pure-Kotlin decoder test; no iroh, no file I/O.
        val obj = buildJsonObject {
            put("id", "conv-x")
            put("agent_id", "agent-x")
            put("conversation_class", "autonomous")
            put("summary", "s")
            put("created_at", "2026-01-01T00:00:00.000Z")
            put("updated_at", "2026-01-02T00:00:00.000Z")
            put("last_message_at", "2026-01-03T00:00:00.000Z")
        }

        val conv = decodeConversation(obj, fallbackAgentId = "fallback")!!
        assertEquals("conv-x", conv.id.value, "id extracted")
        assertEquals("agent-x", conv.agentId.value, "agent_id extracted")
        assertSame(ConversationClass.AUTONOMOUS, conv.effectiveClass, "autonomous class extracted")
        assertEquals("s", conv.summary)
        assertEquals("2026-01-03T00:00:00.000Z", conv.lastMessageAt)

        // Unknown class -> Interactive (the default).
        val interactive = decodeConversation(
            buildJsonObject {
                put("id", "conv-y")
                put("agent_id", "agent-y")
                put("conversation_class", "something_else")
            },
            fallbackAgentId = "fallback",
        )!!
        assertSame(ConversationClass.INTERACTIVE, interactive.effectiveClass)

        // Missing id -> null (the receiver falls through to CreateAndDeliver).
        val dropped = decodeConversation(
            buildJsonObject { put("agent_id", "agent-z") },
            fallbackAgentId = "fallback",
        )
        assertEquals(null, dropped, "missing id must drop the row")

        // Missing agent_id -> falls back to the agent scope.
        val fallback = decodeConversation(
            buildJsonObject { put("id", "conv-w") },
            fallbackAgentId = "scope-agent",
        )!!
        assertEquals("scope-agent", fallback.agentId.value, "missing agent_id must use the scope fallback")
    }

    @Test
    fun `handleDecision logs a2a deliver for RoutingDecision Deliver and calls client input`() {
        // Recording stub: captures the Input command the receiver hands to
        // the client when a Deliver decision lands. The handleDecision
        // path emits a telemetry event AND inputs the message body as a
        // user message on the chosen conversation.
        val captured = mutableListOf<AppServerCommand.Input>()
        val client = RecordingClient(captured)
        val message = IrohAgentMessage(
            fromAgentId = "Meridian",
            toAgentId = "PM-letta-mobile",
            body = "ping",
            msgId = "msg-1",
            ts = 1_700_000_000_000L,
        )
        val decision = IrohAgentMessageRouter.RoutingDecision.Deliver("conv-deliver")

        runBlocking { handleDecision(client, message, decision) }

        assertEquals(1, captured.size, "expected exactly one client.input call")
        val cmd = captured.single()
        assertEquals("PM-letta-mobile", cmd.runtime.agentId, "input runtime targets toAgentId")
        assertEquals("conv-deliver", cmd.runtime.conversationId, "input runtime targets the chosen conversation")
        val payload = cmd.payload as AppServerInputPayload.CreateMessage
        assertEquals(1, payload.messages.size)
        val m = payload.messages.single()
        assertEquals("user", m.role, "decision lands the message as a USER message")
        assertEquals(JsonPrimitive("ping"), m.content, "decision lands the original body verbatim")
        assertEquals("msg-1", m.clientMessageId, "decision forwards the wire msgId for at-most-once on the receiver")
    }

    /**
     * A minimal [AppServerClient] stub: only [input] does anything useful
     * (records the command). Every other entry point throws — `handleDecision`
     * never reaches them on the Deliver path. Keeping the surface tight makes
     * the test fail loudly if `handleDecision` ever starts calling another
     * method under the Deliver branch.
     */
    private class RecordingClient(val captured: MutableList<AppServerCommand.Input>) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            error("unused on Deliver path")
        override suspend fun input(command: AppServerCommand.Input) {
            captured += command
        }
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("unused on Deliver path")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused on Deliver path")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused on Deliver path")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
            error("unused on Deliver path")
    }
}
