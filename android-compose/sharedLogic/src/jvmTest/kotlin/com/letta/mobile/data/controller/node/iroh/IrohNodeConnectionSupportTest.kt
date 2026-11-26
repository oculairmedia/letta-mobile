package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.IrohRelayConfig
import computer.iroh.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy

class IrohNodeConnectionSupportTest {
    @Test
    fun endpointRejectsDuplicateProtocolAlpnsBeforeBinding() {
        assertFailsWith<IllegalArgumentException> {
            IrohNodeEndpoint(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                binding = IrohEndpointBinding(
                    relayConfig = IrohRelayConfig.Default,
                ),
                authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
                protocolHandlers = listOf(object : IrohNodeProtocolHandler {
                    override val alpn = IrohNodeEndpoint.DEFAULT_ALPN.copyOf()
                    override fun authorize(remoteEndpointId: String) = false
                    override suspend fun accept(connection: Connection, remoteEndpointId: String) = Unit
                }),
            )
        }
    }


    /**
     * A direct-only host never gets a home relay, so creating it must not wait for one: it is
     * ready once it has a direct address, and its ticket carries that address so a client can dial.
     */
    @Test
    fun aHostWithRelaysDisabledIsCreatedAndAdvertisesItsDirectAddresses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val endpoint = IrohNodeEndpoint(
            scope = scope,
            binding = IrohEndpointBinding(relayConfig = IrohRelayConfig.Disabled),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
        )
        try {
            val created = kotlinx.coroutines.withTimeoutOrNull(RELAYLESS_CREATE_TIMEOUT_MS) { endpoint.create() }
            assertTrue(created != null, "create() must not wait for a home relay that relays-disabled never gets")
            assertTrue(endpoint.addr().directAddresses().isNotEmpty(), "the host must advertise a direct address")
            assertEquals(null, endpoint.addr().relayUrl(), "a relays-disabled host advertises no relay")
        } finally {
            endpoint.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun endpointRefusesUnregisteredOutboundProtocol() = runBlocking {
        val endpoint = IrohNodeEndpoint(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
        )
        val remote = computer.iroh.EndpointAddr(
            computer.iroh.EndpointId.fromBytes(ByteArray(32)), null, emptyList(),
        )
        assertFailsWith<IllegalArgumentException> {
            endpoint.connect(remote, "/letta/notebook/0".encodeToByteArray())
        }
        Unit
    }

    @Test
    fun peersThatConnectAndLeaveLeaveNoEntryBehind() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val endpoint = IrohNodeEndpoint(scope = scope, authPolicy = IrohAuthPolicy.PairedPeersOnly)
        val turnHost = NodeTurnHost.childOf(scope)
        repeat(FRESH_PEERS) { i ->
            // A handle-less connection fails its first stream accept, as one whose peer hung up at once does.
            endpoint.serveAppServer(Connection(computer.iroh.NoHandle), "%064x".format(i), FakeAppServerController(), turnHost)
        }
        assertEquals(0, endpoint.appServerPeerCount, "a peer with no live connection keeps no entry")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    // ---- ProcessScopedClientMessageDedupe (P3-4, 3wq5g) --------------------

    @Test
    fun dedupeMarksFirstSeenNewAndDuplicatesHandled() {
        val dedupe = ProcessScopedClientMessageDedupe()
        assertTrue(dedupe.markHandled(DEDUPE_AGENT, DEDUPE_CONVERSATION, "a"), "first sight is new")
        assertFalse(dedupe.markHandled(DEDUPE_AGENT, DEDUPE_CONVERSATION, "a"), "second sight is a duplicate")
        assertTrue(dedupe.markHandled(DEDUPE_AGENT, DEDUPE_CONVERSATION, "b"))
        assertTrue(dedupe.contains(DEDUPE_AGENT, DEDUPE_CONVERSATION, "a"))
        assertTrue(dedupe.contains(DEDUPE_AGENT, DEDUPE_CONVERSATION, "b"))
    }

    @Test
    fun theSameClientMessageIdInAnotherConversationOrAgentIsAnotherTurn() {
        val dedupe = ProcessScopedClientMessageDedupe()
        val otherConversation = com.letta.mobile.data.model.ConversationId("conv-dedupe-other")
        val otherAgent = com.letta.mobile.data.model.AgentId("agent-dedupe-other")
        assertTrue(dedupe.markHandled(DEDUPE_AGENT, DEDUPE_CONVERSATION, "otid-1"))
        assertTrue(dedupe.markHandled(DEDUPE_AGENT, otherConversation, "otid-1"), "another conversation's turn runs")
        assertTrue(dedupe.markHandled(otherAgent, DEDUPE_CONVERSATION, "otid-1"), "another agent's turn runs")
        assertFalse(dedupe.markHandled(DEDUPE_AGENT, otherConversation, "otid-1"), "a re-send there is still a duplicate")
    }

    @Test
    fun dedupeTrimsOldestWhenOverCapacity() {
        val dedupe = ProcessScopedClientMessageDedupe(maxIds = 4, trimCount = 2)
        fun mark(id: String) = dedupe.markHandled(DEDUPE_AGENT, DEDUPE_CONVERSATION, id)
        fun seen(id: String) = dedupe.contains(DEDUPE_AGENT, DEDUPE_CONVERSATION, id)
        // Fill to capacity.
        listOf("1", "2", "3", "4").forEach { assertTrue(mark(it)) }
        assertEquals(4, dedupe.size())
        // One more triggers a trim of the 2 oldest (1, 2), then adds 5.
        assertTrue(mark("5"))
        assertEquals(3, dedupe.size())
        assertFalse(seen("1"))
        assertFalse(seen("2"))
        assertTrue(seen("3"))
        assertTrue(seen("5"))
        // A trimmed id can be re-added (the reconnect-window trade-off).
        assertTrue(mark("1"))
    }

    // ---- ConnectionEventSeq / IrohEventSeqAllocator (P3-4 race) -----------

    @Test
    fun connectionEventSeqIsStrictlyMonotonic() {
        val seq = ConnectionEventSeq(base = 10L)
        assertEquals(10L, seq.next())
        assertEquals(11L, seq.next())
        assertEquals(12L, seq.next())
    }

    @Test
    fun differentConnectionsDrawDisjointRanges() {
        val a = IrohEventSeqAllocator.newConnectionSeq()
        val b = IrohEventSeqAllocator.newConnectionSeq()
        val aFirst = a.next()
        val bFirst = b.next()
        // Disjoint bases (>= one stride apart) — a connection's whole run of
        // seqs cannot collide with another's within one process.
        assertTrue(
            kotlin.math.abs(bFirst - aFirst) >= IrohEventSeqAllocator.STRIDE,
            "expected disjoint bases at least one stride apart, got a=$aFirst b=$bFirst",
        )
    }

    // ---- OpenToolCallTracker + DanglingToolCallSynthesizer (P3-5, 8s45p) --

    @Test
    fun trackerReportsOpenToolCallUntilItsReturnArrives() {
        val tracker = OpenToolCallTracker()
        tracker.observe(streamDelta(""" "message_type":"tool_call_message","tool_call_id":"tc-1","tool_name":"grep" """))
        assertEquals(listOf("tc-1"), tracker.openIds())
        // The matching tool_return closes it.
        tracker.observe(streamDelta(""" "message_type":"tool_return_message","tool_call_id":"tc-1","status":"success" """))
        assertTrue(tracker.isEmpty())
    }

    @Test
    fun trackerKeepsUnreturnedCallsOpen() {
        val tracker = OpenToolCallTracker()
        tracker.observe(streamDelta(""" "message_type":"tool_call_message","tool_call_id":"tc-1" """))
        tracker.observe(streamDelta(""" "message_type":"tool_call_message","tool_call_id":"tc-2" """))
        tracker.observe(streamDelta(""" "message_type":"tool_return_message","tool_call_id":"tc-1","status":"success" """))
        assertEquals(listOf("tc-2"), tracker.openIds(), "only the unreturned call stays open")
    }

    @Test
    fun cancelledToolReturnDeltaIsAnErrorCancelledReturn() {
        val delta = DanglingToolCallSynthesizer.cancelledToolReturnDelta("tc-9")
        assertEquals("tool_return_message", delta["message_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tc-9", delta["tool_call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("error", delta["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("cancelled", delta["tool_return"]?.jsonPrimitive?.contentOrNull)
    }

    private fun streamDelta(deltaFields: String): String =
        """{"type":"stream_delta","event_seq":1,"delta":{${deltaFields.trim()}}}"""

    @Test
    fun tagStreamDelta_assistant_getsCmStreamOtidId() {
        val delta = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("letta-msg-3255"))
            put("message_type", kotlinx.serialization.json.JsonPrimitive("assistant_message"))
            put("otid", kotlinx.serialization.json.JsonPrimitive("provider-assistant-1-abc"))
        }
        val tagged = tagStreamDeltaForOptimisticDedup(delta)
        assertEquals("cm-stream-provider-assistant-1-abc", tagged["id"]?.jsonPrimitive?.contentOrNull)
        // otid + message_type preserved
        assertEquals("provider-assistant-1-abc", tagged["otid"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun tagStreamDelta_prefersMessageIdAndKeepsDistinctMessagesInOneRunDistinct() {
        fun delta(id: String, messageId: String) = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("message_id", kotlinx.serialization.json.JsonPrimitive(messageId))
            put("message_type", kotlinx.serialization.json.JsonPrimitive("assistant_message"))
            put("run_id", kotlinx.serialization.json.JsonPrimitive("one-run"))
            put("turn_id", kotlinx.serialization.json.JsonPrimitive("one-turn"))
        }
        assertEquals("cm-stream-logical-a", tagStreamDeltaForOptimisticDedup(delta("delivery-a", "logical-a"))["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("cm-stream-logical-b", tagStreamDeltaForOptimisticDedup(delta("delivery-b", "logical-b"))["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun tagStreamDelta_skipsBlankAliasesAndUsesNextStableValue() {
        val delta = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("delivery"))
            put("message_id", kotlinx.serialization.json.JsonPrimitive(" "))
            put("otid", kotlinx.serialization.json.JsonPrimitive("stable-otid"))
            put("message_type", kotlinx.serialization.json.JsonPrimitive("assistant_message"))
        }
        assertEquals(
            "cm-stream-stable-otid",
            tagStreamDeltaForOptimisticDedup(delta)["id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun tagStreamDelta_reasoning_getsCmReasonId() {
        val delta = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("letta-msg-9"))
            put("message_type", kotlinx.serialization.json.JsonPrimitive("reasoning_message"))
            put("otid", kotlinx.serialization.json.JsonPrimitive("otid-r"))
        }
        assertEquals("cm-reason-otid-r", tagStreamDeltaForOptimisticDedup(delta)["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun tagStreamDelta_noOtid_or_toolCall_unchanged() {
        val noOtid = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("letta-msg-1"))
            put("message_type", kotlinx.serialization.json.JsonPrimitive("assistant_message"))
        }
        assertEquals("letta-msg-1", tagStreamDeltaForOptimisticDedup(noOtid)["id"]?.jsonPrimitive?.contentOrNull)
        val tool = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("toolcall-x"))
            put("message_type", kotlinx.serialization.json.JsonPrimitive("tool_call_message"))
            put("otid", kotlinx.serialization.json.JsonPrimitive("o"))
        }
        assertEquals("toolcall-x", tagStreamDeltaForOptimisticDedup(tool)["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun tagStreamDelta_idempotent() {
        val already = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("cm-stream-o1"))
            put("message_type", kotlinx.serialization.json.JsonPrimitive("assistant_message"))
            put("otid", kotlinx.serialization.json.JsonPrimitive("o1"))
        }
        assertEquals("cm-stream-o1", tagStreamDeltaForOptimisticDedup(already)["id"]?.jsonPrimitive?.contentOrNull)
    }


    @Test
    fun retagStreamDeltaFrame_tagsAssistantDeltaInsideWireFrame() {
        val raw = """{"type":"stream_delta","event_seq":5,"delta":{"id":"letta-msg-3255","message_type":"assistant_message","otid":"provider-assistant-1-abc","content":"Hey"}}"""
        val out = retagStreamDeltaFrameForOptimisticDedup(raw)
        val delta = kotlinx.serialization.json.Json.parseToJsonElement(out).jsonObject["delta"]!!.jsonObject
        assertEquals("cm-stream-provider-assistant-1-abc", delta["id"]?.jsonPrimitive?.contentOrNull)
        // structure preserved
        assertEquals("assistant_message", delta["message_type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun retagStreamDeltaFrame_nonStreamDelta_unchanged() {
        val raw = """{"type":"turn_done","status":"completed"}"""
        assertEquals(raw, retagStreamDeltaFrameForOptimisticDedup(raw))
    }


    private companion object {
        const val FRESH_PEERS = 200
        const val RELAYLESS_CREATE_TIMEOUT_MS = 15_000L
        val DEDUPE_AGENT = com.letta.mobile.data.model.AgentId("agent-dedupe")
        val DEDUPE_CONVERSATION = com.letta.mobile.data.model.ConversationId("conv-dedupe")
    }
}
