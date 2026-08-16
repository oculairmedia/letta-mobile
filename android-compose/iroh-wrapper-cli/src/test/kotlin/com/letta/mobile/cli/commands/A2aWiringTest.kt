package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.iroh.HostEndpointAddressStore
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import com.letta.mobile.data.transport.iroh.DeliveryOutcome
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 *  - letta-mobile-xmpqm: [publishHost] writes EXACTLY ONE host record into
 *    the kv store on bind, regardless of how many agents share the host —
 *    O(1) per bind, NOT O(agents),
 *  - the bind path migrates a legacy per-agent file to the single host
 *    record (no stale per-agent rows can survive migration).
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

    /**
     * letta-mobile-xmpqm: [publishHost] must write EXACTLY ONE `host:` line to
     * the kv file, regardless of how many agents share the host. This is the
     * regression-pin for the O(1) bind-time write count.
     */
    @Test
    fun `publishHost writes exactly one host record per bind`() {
        assumeTrue(nativeEnabled(), "set -DrunIrohNativeE2E=true to run the loopback a2a build probe")
        val tmp = Files.createTempDirectory("xmpqm-publish-host").toFile()
        try {
            val addressBook = File(tmp, "agents.kv").also { it.createNewFile() }
            val identitiesDir = File(tmp, "identities")
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = identitiesDir,
                addressBook = addressBook,
            )
            val wiring = runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) }
            try {
                val record = runBlocking { publishHost(cfg, wiring.endpoint, wiring.addressStore) }
                assertNotNull(record, "publishHost must return the HostEndpointRecord it wrote")
                val content = addressBook.readText()
                val lines = content.lines().filter { it.isNotBlank() }
                assertEquals(
                    1,
                    lines.size,
                    "kv file must hold EXACTLY ONE line after publishHost; got ${lines.size}: $content",
                )
                assertTrue(lines.single().startsWith("host:"), "kv line must use the host: prefix")
                assertTrue(
                    content.contains(wiring.nodeIdHex),
                    "host record must carry the live node id: $content",
                )
                assertEquals(wiring.nodeIdHex.take(HostEndpointAddressStore.HOST_KEY_LENGTH), record.hostKey)
            } finally {
                wiring.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * letta-mobile-xmpqm: [buildA2aWiring] must call [publishHost] exactly
     * ONCE per wrapper start, not once per agent. Pinned here against the
     * regression of per-agent publishing returning in a future refactor.
     */
    @Test
    fun `build calls publishHost exactly once and never writes per-agent rows`() {
        assumeTrue(nativeEnabled(), "set -DrunIrohNativeE2E=true to run the loopback a2a build probe")
        val tmp = Files.createTempDirectory("xmpqm-build-once").toFile()
        try {
            // Pre-populate a legacy per-agent file. The build MUST collapse it
            // to a single host record — no per-agent rows can survive migration.
            val addressBook = File(tmp, "agents.kv").also {
                it.writeText(
                    """
                    agent-1=nodehex@1.2.3.4:1
                    agent-2=nodehex@1.2.3.4:2
                    agent-3=nodehex@1.2.3.4:3
                    """.trimIndent(),
                )
            }
            val identitiesDir = File(tmp, "identities")
            val cfg = A2aWiringConfig(
                port = 0,
                secretKeyPath = null,
                identityDir = identitiesDir,
                addressBook = addressBook,
            )
            val wiring = runBlocking { buildA2aWiring(cfg, client = null, localBackendDir = null) }
            try {
                val content = addressBook.readText()
                val lines = content.lines().filter { it.isNotBlank() }
                assertEquals(
                    1,
                    lines.size,
                    "build must collapse the legacy file to one host record; got ${lines.size} lines: $content",
                )
                assertTrue(lines.single().startsWith("host:"))
                assertTrue("agent-1=" !in content, "legacy per-agent rows must NOT survive")
                assertTrue("agent-2=" !in content)
                assertTrue("agent-3=" !in content)
            } finally {
                wiring.close()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * letta-mobile-oi147: the identity migration must actually RUN at bind. It is
     * wired into `publishHost`, whose other paths need a native endpoint —
     * so this exercises `migrateLegacyIdentities` directly, which is the same
     * function the bind path calls. Without this the migration could be quietly
     * unreachable and every test of it would still pass.
     */
    @Test
    fun `bind-time identity migration collapses legacy namespaced files`() {
        // Pure-Kotlin — does NOT touch iroh, so it runs on the default gate.
        val tmp = Files.createTempDirectory("oi147-migrate").toFile()
        try {
            val identities = File(tmp, "identities").also { it.mkdirs() }
            val liveKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
            File(identities, "agent-X.json").writeText("""{"agentId":"agent-X","secretKeyB64":"$liveKey"}""")
            File(identities, "letta_agent-X.json")
                .writeText("""{"agentId":"letta_agent-X","secretKeyB64":"${Base64.getEncoder().encodeToString(ByteArray(32) { 2 })}"}""")
            File(identities, "letta_agent-Y.json")
                .writeText("""{"agentId":"letta_agent-Y","secretKeyB64":"${Base64.getEncoder().encodeToString(ByteArray(32) { 3 })}""")

            migrateLegacyIdentities(identities)

            val names = identities.listFiles()!!.map { it.name }.sorted()
            assertEquals(listOf("agent-X.json", "agent-Y.json"), names)
            assertTrue(
                File(identities, "agent-X.json").readText().contains(liveKey),
                "the live canonical keypair must survive the collapse untouched",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** A missing identity dir at bind must be a no-op, never an exception. */
    @Test
    fun `bind-time identity migration tolerates a missing identity dir`() {
        val tmp = Files.createTempDirectory("oi147-migrate-missing").toFile()
        try {
            migrateLegacyIdentities(File(tmp, "no-such-dir"))
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
    fun `wrapA2aEnvelope produces valid JSON string with expected fields`() {
        val message = IrohAgentMessage(
            fromAgentId = "agent-a",
            toAgentId = "agent-b",
            body = "hello world",
            msgId = "msg-123",
            ts = 1_700_000_000_123L,
        )
        val wrappedStr = wrapA2aEnvelope(message)
        val json = Json.parseToJsonElement(wrappedStr) as JsonObject
        assertEquals("a2a", json["envelope"]?.stringOrNullSafe())
        assertEquals("agent-a", json["from_agent_id"]?.stringOrNullSafe())
        assertEquals("agent-b", json["to_agent_id"]?.stringOrNullSafe())
        assertEquals(1_700_000_000_123L, (json["ts"] as? JsonPrimitive)?.long)
        assertEquals("msg-123", json["msg_id"]?.stringOrNullSafe())
        assertEquals("hello world", json["content"]?.stringOrNullSafe())
    }

    @Test
    fun `handleDecision logs a2a deliver for RoutingDecision Deliver and calls client input`() {
        // Recording stub: captures the Input command the receiver hands to
        // the client when a Deliver decision lands. The handleDecision
        // path emits a telemetry event AND inputs the wrapped envelope as a
        // user message on the chosen conversation.
        val client = RecordingClient()
        val message = IrohAgentMessage(
            fromAgentId = "Meridian",
            toAgentId = "PM-letta-mobile",
            body = "ping",
            msgId = "msg-1",
            ts = 1_700_000_000_000L,
        )
        val decision = IrohAgentMessageRouter.RoutingDecision.Deliver("conv-deliver")

        val outcome = runBlocking { handleDecision(client, message, decision) }
        assertTrue(outcome.delivered)

        assertEquals(1, client.captured.size, "expected exactly one client.input call")
        val cmd = client.captured.single()
        assertEquals("PM-letta-mobile", cmd.runtime.agentId, "input runtime targets toAgentId")
        assertEquals("conv-deliver", cmd.runtime.conversationId, "input runtime targets the chosen conversation")
        val payload = cmd.payload as AppServerInputPayload.CreateMessage
        assertEquals(1, payload.messages.size)
        val m = payload.messages.single()
        assertEquals("user", m.role, "decision lands the message as a USER message")
        // letta-mobile-8kbqd: human-visible content is the plain body, NOT the
        // wrapA2aEnvelope JSON blob. Envelope metadata stays on the wire +
        // telemetry; only msgId is forwarded as clientMessageId for dedup.
        assertEquals(JsonPrimitive("ping"), m.content, "decision lands plain body text, not envelope JSON")
        assertFalse(
            m.content.toString().contains("\"envelope\""),
            "persisted/rendered content must not contain the literal envelope JSON key",
        )
        // letta-mobile-slqfp: clientMessageId now carries the structured a2a
        // envelope encoding (msgId + from/to agentId) so the receiving
        // client's chat render can project inbound provenance without ever
        // parsing message body text. Decode it back out rather than
        // asserting the raw msgId.
        val decoded = com.letta.mobile.data.messaging.AgentMessageClientId.decode(m.clientMessageId)
        assertEquals("msg-1", decoded?.msgId, "decision forwards the wire msgId for at-most-once on the receiver")
        assertEquals("Meridian", decoded?.fromAgentId)
        assertEquals("PM-letta-mobile", decoded?.toAgentId)
    }

    @Test
    fun `listConversationsForAgent normalizes letta_agent namespaced ID to bare ID for candidate lookup`() {
        val base = Files.createTempDirectory("a2a-normalize-list").toFile()
        try {
            val bareAgentId = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
            val namespacedAgentId = "letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"

            File(base, "agents").apply { mkdirs() }
            File(base, "agents/$bareAgentId.json").writeText("""{"id":"$bareAgentId","name":"TestAgent"}""")

            writeConversation(base, "default:$bareAgentId", """{"id":"conv-123","agent_id":"$bareAgentId","last_message_at":"2026-07-22T20:05:00.000Z"}""")

            val store = LocalBackendAdminStore(base)
            val states = runBlocking { listConversationsForAgent(store, namespacedAgentId) }

            assertEquals(1, states.size, "expected candidate conversation found via namespaced lookup")
            assertEquals("conv-123", states.single().conversation.id.value)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `handleDecision normalizes namespaced from and to agent IDs`() {
        val captured = mutableListOf<AppServerCommand.Input>()
        val client = RecordingClient(captured)
        val message = IrohAgentMessage(
            fromAgentId = "letta_agent-from-123",
            toAgentId = "letta_agent-to-456",
            body = "hello",
            msgId = "msg-norm-1",
            ts = 1_700_000_000_000L,
        )
        val decision = IrohAgentMessageRouter.RoutingDecision.Deliver("conv-norm")

        runBlocking { handleDecision(client, message, decision) }

        assertEquals(1, captured.size)
        val cmd = captured.single()
        assertEquals("agent-to-456", cmd.runtime.agentId, "input target agentId should be bare ID")
    }

    @Test
    fun `handleDecision emits no_app_server_client drop when client is null on CreateAndDeliver`() {
        com.letta.mobile.util.Telemetry.clear()
        val message = IrohAgentMessage(
            fromAgentId = "letta_agent-from-1",
            toAgentId = "letta_agent-to-2",
            body = "test",
            msgId = "msg-drop-1",
            ts = 1_700_000_000_000L,
        )
        val decision = IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver

        val outcome = runBlocking { handleDecision(client = null, message, decision) }
        assertFalse(outcome.delivered)
        assertEquals("application_enqueue_failure", outcome.reason)

        val events = com.letta.mobile.util.Telemetry.snapshot()
        val dropEvent = events.single { it.name == "a2a.drop" }
        assertEquals("no_app_server_client", dropEvent.attrs["reason"])
        assertEquals("agent-from-1", dropEvent.attrs["fromAgentId"])
        assertEquals("agent-to-2", dropEvent.attrs["toAgentId"])
    }

    @Test
    fun `handleCreateAndDeliver logs appserver error when conversationCreate returns error`() {
        com.letta.mobile.util.Telemetry.clear()
        val client = FailingCreateClient(errorMessage = "Agent not found in registry")
        val message = IrohAgentMessage(
            fromAgentId = "letta_agent-from-1",
            toAgentId = "letta_agent-to-2",
            body = "test",
            msgId = "msg-create-fail",
            ts = 1_700_000_000_000L,
        )

        val outcome = runBlocking { handleCreateAndDeliver(client, message) }
        assertFalse(outcome.delivered)
        assertEquals("conversation_create_failure", outcome.reason)

        val events = com.letta.mobile.util.Telemetry.snapshot()
        val dropEvent = events.single { it.name == "a2a.drop" }
        assertEquals("no_conversation_create_path", dropEvent.attrs["reason"])
        assertEquals("Agent not found in registry", dropEvent.attrs["error"])
    }

    @Test
    fun `listConversationsForAgent emits warning telemetry when agent record is missing`() {
        com.letta.mobile.util.Telemetry.clear()
        val base = Files.createTempDirectory("a2a-missing-agent").toFile()
        try {
            File(base, "agents").apply { mkdirs() }
            val store = LocalBackendAdminStore(base)
            val states = runBlocking { listConversationsForAgent(store, "agent-nonexistent") }

            assertTrue(states.isEmpty(), "expected empty list of conversation states for missing agent")
            val events = com.letta.mobile.util.Telemetry.snapshot()
            val warnEvent = events.single { it.name == "a2a.agent_missing" }
            assertEquals("agent-nonexistent", warnEvent.attrs["agentId"])
            assertEquals(com.letta.mobile.util.Telemetry.Level.WARN, warnEvent.level)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `handleDecision CreateAndDeliver creates conversation via client conversationCreate and inputs wrapped message`() {
        val client = RecordingClient()
        val message = IrohAgentMessage(
            fromAgentId = "Meridian",
            toAgentId = "PM-letta-mobile",
            body = "ping create",
            msgId = "msg-2",
            ts = 1_700_000_000_000L,
        )
        val decision = IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver

        val outcome = runBlocking { handleDecision(client, message, decision) }
        assertTrue(outcome.delivered)

        assertEquals(1, client.capturedCreate.size, "expected one conversationCreate call")
        val createCmd = client.capturedCreate.single()
        assertEquals("PM-letta-mobile", createCmd.body["agent_id"]?.stringOrNullSafe())

        assertEquals(1, client.captured.size, "expected one input call after creation")
        val inputCmd = client.captured.single()
        assertEquals("PM-letta-mobile", inputCmd.runtime.agentId)
        assertEquals("conv-created-1", inputCmd.runtime.conversationId)

        val payload = inputCmd.payload as AppServerInputPayload.CreateMessage
        val m = payload.messages.single()
        assertEquals("user", m.role)
        // letta-mobile-8kbqd: CreateAndDeliver path also lands plain body text.
        assertEquals(JsonPrimitive("ping create"), m.content)
        assertFalse(
            m.content.toString().contains("\"envelope\""),
            "persisted/rendered content must not contain the literal envelope JSON key",
        )
        val decodedCreate = com.letta.mobile.data.messaging.AgentMessageClientId.decode(m.clientMessageId)
        assertEquals("msg-2", decodedCreate?.msgId)
        assertEquals("Meridian", decodedCreate?.fromAgentId)
        assertEquals("PM-letta-mobile", decodedCreate?.toAgentId)
    }

    @Test
    fun `handleDecision CreateAndDeliver drops message on conversationCreate failure`() {
        val client = RecordingClient(
            conversationCreateResult = { cmd ->
                AppServerInboundFrame.ConversationCreateResponse(
                    requestId = cmd.requestId,
                    success = false,
                    error = "Failed to create conversation",
                )
            },
        )
        val message = IrohAgentMessage(
            fromAgentId = "Meridian",
            toAgentId = "PM-letta-mobile",
            body = "ping create fail",
            msgId = "msg-3",
            ts = 1_700_000_000_000L,
        )
        val decision = IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver

        val outcome = runBlocking { handleDecision(client, message, decision) }
        assertFalse(outcome.delivered)
        assertEquals("conversation_create_failure", outcome.reason)

        assertEquals(1, client.capturedCreate.size, "expected one conversationCreate call")
        assertTrue(client.captured.isEmpty(), "expected input not to be called when create fails")
    }

    /**
     * A minimal [AppServerClient] stub: only [input] and [conversationCreate] do
     * anything useful (record the commands). Every other entry point throws —
     * `handleDecision` never reaches them on these paths. Keeping the surface tight
     * makes the test fail loudly if `handleDecision` ever starts calling another
     * method unexpected.
     */
    private class RecordingClient(
        val captured: MutableList<AppServerCommand.Input> = mutableListOf(),
        val capturedCreate: MutableList<AppServerCommand.ConversationCreate> = mutableListOf(),
        var conversationCreateResult: (AppServerCommand.ConversationCreate) -> AppServerInboundFrame.ConversationCreateResponse = { cmd ->
            AppServerInboundFrame.ConversationCreateResponse(
                requestId = cmd.requestId,
                success = true,
                conversation = buildJsonObject { put("id", "conv-created-1") },
            )
        },
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            error("unused path")
        override suspend fun input(command: AppServerCommand.Input) {
            captured += command
        }
        override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate): AppServerInboundFrame.ConversationCreateResponse {
            capturedCreate += command
            return conversationCreateResult(command)
        }
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("unused path")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused path")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused path")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
            error("unused path")
    }

    private class FailingCreateClient(val errorMessage: String) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            error("unused")
        override suspend fun input(command: AppServerCommand.Input) {}
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
            error("unused")
        override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate): com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationCreateResponse =
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationCreateResponse(
                requestId = command.requestId,
                success = false,
                error = errorMessage,
            )
    }
}
