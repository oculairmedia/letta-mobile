package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IrohAdminRpcChatGatewayTest {

    @Test
    fun setConversationSummaryUsesConversationUpdateRpc() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            assertEquals("conversation.update", call.method)
            assertEquals("/v1/conversations/conv-1", call.path)
            assertTrue(call.body.orEmpty().contains("\"summary\":\"Plan the release\""))
            ok("""{"id":"conv-1","agent_id":"agent-1","summary":"Plan the release"}""")
        }
        val gateway = IrohAdminRpcChatGateway(transport)

        val updated = gateway.setConversationSummary(
            ConversationSummaryUpdate(ConversationId("conv-1"), ConversationSummary("Plan the release")),
        )

        assertEquals("Plan the release", updated.summary)
    }

    @Test
    fun listConversationsDecodesAdminRpcResult() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            assertEquals("conversation.list", call.method)
            assertEquals("/v1/conversations", call.path)
            ok("""[{"id":"conv-1","agent_id":"agent-1","summary":"hi"}]""")
        }
        val gateway = IrohAdminRpcChatGateway(transport)

        val conversations = gateway.listConversations(limit = 10, archiveStatus = "active")

        assertEquals(1, conversations.size)
        assertEquals("conv-1", conversations.single().id.value)
        assertEquals("agent-1", conversations.single().agentId.value)
        val body = transport.rpcCalls.single().body.orEmpty()
        assertTrue("archive_status" in body && "\"10\"" in body, "body should carry stringified limit + filter: $body")
    }

    @Test
    fun listConversationMessagesDecodesGuardWrappedPage_c4igq9() = runTest(UnconfinedTestDispatcher()) {
        // c4igq.9: when the serve-side page guard trims an oversized window it wraps
        // the array as { messages: [...], has_more, next_before }. The client must
        // still decode it (not only a bare array).
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            if (call.method == "message.list") {
                ok("""{"messages":[{"id":"m-1","message_type":"assistant_message","otid":"m-1"}],"has_more":true,"next_before":"m-1"}""")
            } else ok("""{"id":"conv-1","agent_id":"agent-1"}""")
        }
        val gateway = IrohAdminRpcChatGateway(transport)
        val messages = gateway.listConversationMessages("conv-1", limit = 50, after = null, order = "desc")
        assertEquals(1, messages.size)
        assertEquals("m-1", messages.single().id)
    }

    @Test
    fun listConversationMessagesBuildsQueryStringPath() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            assertEquals("message.list", call.method)
            assertEquals("/v1/conversations/conv-1/messages?limit=50&order=desc", call.path)
            assertEquals(null, call.body)
            ok("""[{"message_type":"assistant_message","id":"msg-1","content":"hello"}]""")
        }
        val gateway = IrohAdminRpcChatGateway(transport)

        val messages = gateway.listConversationMessages("conv-1", limit = 50, after = null, order = "desc")

        assertEquals(listOf("msg-1"), messages.map { it.id })
    }

    @Test
    fun sendStreamsTurnDeltasAndCompletesOnTurnDone() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { _ -> ok("""{"id":"conv-1","agent_id":"agent-1"}""") }
        val gateway = IrohAdminRpcChatGateway(transport)
        val turn = TurnRef(conversationId = "conv-1", turnId = "turn-1")

        val collected = async { gateway.sendConversationMessage("conv-1", request("hello")).toList() }
        transport.frameEvents.subscriptionCount.first { it > 0 }
        transport.emitFrame(turnStarted(turn))
        transport.emitFrame(assistantDelta(turn, id = "cm-stream-1", content = "Hi there"))
        transport.emitFrame(turnDone(turn, status = "completed"))

        val messages = collected.await()
        assertEquals(listOf("cm-stream-1"), messages.map { it.id })
        assertEquals("agent-1", transport.sends.single().agentId)
        assertEquals("hello", transport.sends.single().text)
    }

    @Test
    fun sendFailsWhenTurnFails() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { _ -> ok("""{"id":"conv-1","agent_id":"agent-1"}""") }
        val gateway = IrohAdminRpcChatGateway(transport)
        val turn = TurnRef(conversationId = "conv-1", turnId = "turn-9")

        val collected = async { runCatching { gateway.sendConversationMessage("conv-1", request("boom")).toList() } }
        transport.frameEvents.subscriptionCount.first { it > 0 }
        transport.emitFrame(turnStarted(turn))
        transport.emitFrame(turnDone(turn, status = "failed"))

        val failure = collected.await().exceptionOrNull()
        assertTrue(failure is TimelineTransportHttpException, "expected transport failure, got $failure")
    }

    @Test
    fun sendThrowsWhenTransportRejectsDispatch() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport(sendAccepts = false)
        transport.rpcResponder = { _ -> ok("""{"id":"conv-1","agent_id":"agent-1"}""") }
        val gateway = IrohAdminRpcChatGateway(transport)

        assertFailsWith<TimelineTransportHttpException> {
            gateway.sendConversationMessage("conv-1", request("hello")).toList()
        }
    }

    @Test
    fun streamConversationRoutesDeltasByActiveTurnConversation() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        val gateway = IrohAdminRpcChatGateway(transport, heartbeatIntervalMs = 600_000)
        val turnA = TurnRef(conversationId = "conv-a", turnId = "turn-a")
        val turnB = TurnRef(conversationId = "conv-b", turnId = "turn-b")
        val received = mutableListOf<TimelineStreamFrame>()
        val collector = launch {
            gateway.streamConversation("conv-a").collect { received += it }
        }
        transport.frameEvents.subscriptionCount.first { it > 0 }

        transport.emitFrame(turnStarted(turnA))
        transport.emitFrame(assistantDelta(turnA, id = "cm-stream-a", content = "for a"))
        transport.emitFrame(turnDone(turnA, status = "completed"))
        transport.emitFrame(turnStarted(turnB))
        transport.emitFrame(assistantDelta(turnB, id = "cm-stream-b", content = "for b"))
        collector.cancel()

        val messageIds = received.filterIsInstance<TimelineStreamFrame.Message>().map { it.message.id }
        assertEquals(listOf("cm-stream-a"), messageIds)
    }

    @Test
    fun streamConversationDeliversInitiatorTurnFramesLiveDuringSend() = runTest(UnconfinedTestDispatcher()) {
        // c4igq.8: on the INITIATOR's own turn, the UI's continuous
        // streamConversation collector must receive the turn's frames LIVE as
        // they arrive — not buffered until a subsequent send. Reproduces the
        // device symptom: a single-tool turn goes dark until the next send.
        val transport = FakeIrohTransport()
        transport.rpcResponder = { _ -> ok("""{"id":"conv-i","agent_id":"agent-i"}""") }
        val gateway = IrohAdminRpcChatGateway(transport, heartbeatIntervalMs = 600_000)
        val turn = TurnRef(conversationId = "conv-i", turnId = "turn-i")

        // The UI's persistent stream subscriber: continuous, opened BEFORE the send.
        val streamed = mutableListOf<TimelineStreamFrame>()
        val streamCollector = launch {
            gateway.streamConversation("conv-i").collect { streamed += it }
        }
        transport.frameEvents.subscriptionCount.first { it > 0 }

        // The initiator send runs concurrently (its own bridge.events collector).
        val send = launch {
            gateway.sendConversationMessage("conv-i", request("go")).collect { /* drain the send flow */ }
        }
        // Wait until BOTH the stream and the send collectors are subscribed.
        transport.frameEvents.subscriptionCount.first { it >= 2 }

        // The turn's frames arrive. NO second send.
        transport.emitFrame(turnStarted(turn))
        transport.emitFrame(assistantDelta(turn, id = "cm-stream-i", content = "live text", tagged = true))
        transport.emitFrame(turnDone(turn, status = "completed"))
        send.join()
        streamCollector.cancel()

        // The streamConversation collector MUST have seen the assistant delta LIVE.
        val streamedMessageIds = streamed.filterIsInstance<TimelineStreamFrame.Message>().map { it.message.id }
        assertTrue(
            streamedMessageIds.contains("cm-stream-i"),
            "the UI streamConversation collector must receive the initiator turn's delta LIVE during the turn — got $streamedMessageIds",
        )
    }

    @Test
    fun streamConversationEmitsFannedOutFramesForPassiveObserver() = runTest(UnconfinedTestDispatcher()) {
        // letta-mobile-r3i1z: a passive observer (conversation open, NO local
        // send) receives the fanned-out turn frames of another client's turn.
        // The user_message echo arrives BEFORE turn_started, so routing must
        // key on the frame's own conversation_id — an active-turn-only gate
        // dropped it (and, transitively, everything the observer should see).
        val transport = FakeIrohTransport()
        val gateway = IrohAdminRpcChatGateway(transport, heartbeatIntervalMs = 600_000)
        val turnObs = TurnRef(conversationId = "conv-obs", turnId = "turn-obs")
        val received = mutableListOf<TimelineStreamFrame>()
        val collector = launch {
            gateway.streamConversation("conv-obs").collect { received += it }
        }
        transport.frameEvents.subscriptionCount.first { it > 0 }

        // Fanned-out shape: user echo first, then turn_started, deltas, terminal.
        transport.emitFrame(userMessage(turnObs, id = "user-echo-1", content = "hi from the other device"))
        transport.emitFrame(turnStarted(turnObs))
        transport.emitFrame(assistantDelta(turnObs, id = "cm-stream-obs", content = "observed reply", tagged = true))
        transport.emitFrame(turnDone(turnObs, status = "completed"))
        collector.cancel()

        val messageIds = received.filterIsInstance<TimelineStreamFrame.Message>().map { it.message.id }
        assertEquals(listOf("user-echo-1", "cm-stream-obs"), messageIds)
    }

    @Test
    fun streamConversationNeverLeaksConversationTaggedFramesOfOtherConversations() = runTest(UnconfinedTestDispatcher()) {
        // letta-mobile-r3i1z: the frame's own conversation tag is
        // authoritative — even while conv-a's turn is active, a fanned-out
        // frame tagged conv-b must not surface on streamConversation("conv-a").
        val transport = FakeIrohTransport()
        val gateway = IrohAdminRpcChatGateway(transport, heartbeatIntervalMs = 600_000)
        val turnA = TurnRef(conversationId = "conv-a", turnId = "turn-a")
        val turnB = TurnRef(conversationId = "conv-b", turnId = "turn-b")
        val received = mutableListOf<TimelineStreamFrame>()
        val collector = launch {
            gateway.streamConversation("conv-a").collect { received += it }
        }
        transport.frameEvents.subscriptionCount.first { it > 0 }

        transport.emitFrame(turnStarted(turnA))
        transport.emitFrame(assistantDelta(turnA, id = "cm-stream-a", content = "for a"))
        // Tagged frames from another viewed conversation arriving mid-turn.
        transport.emitFrame(userMessage(turnB, id = "user-echo-b", content = "for b"))
        transport.emitFrame(assistantDelta(turnB, id = "cm-stream-b", content = "for b", tagged = true))
        transport.emitFrame(turnDone(turnA, status = "completed"))
        collector.cancel()

        val messageIds = received.filterIsInstance<TimelineStreamFrame.Message>().map { it.message.id }
        assertEquals(listOf("cm-stream-a"), messageIds)
    }

    @Test
    fun listAgentMessagesDegradesToEmptyOverIroh() = runTest(UnconfinedTestDispatcher()) {
        // No agent-scoped message.list handler exists over Iroh — degrade to
        // empty (keeps a default-shim conversation functional) instead of
        // throwing a load error.
        val gateway = IrohAdminRpcChatGateway(FakeIrohTransport())
        assertEquals(emptyList(), gateway.listAgentMessages("agent-1"))
    }

    @Test
    fun directoryUpdateAgentRoutesAgentUpdateWithMergedBody() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            assertEquals("agent.update", call.method)
            assertEquals("/v1/agents/agent-1", call.path)
            assertTrue(call.body.orEmpty().contains("\"agent_id\":\"agent-1\""))
            assertTrue(call.body.orEmpty().contains("\"name\":\"Renamed\""))
            ok("""{"id":"agent-1","name":"Renamed"}""")
        }
        val directory = IrohAdminRpcAgentDirectory(transport)
        val agent = directory.updateAgent("agent-1", AgentUpdateParams(name = "Renamed"))
        assertEquals("agent-1", agent.id.value)
        assertEquals("Renamed", agent.name)
    }

    @Test
    fun directoryCreateScheduleRoutesAgentScopedScheduleCreate() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohTransport()
        transport.rpcResponder = { call ->
            assertEquals("schedule.create", call.method)
            assertEquals("/v1/agents/agent-1/schedule", call.path)
            assertTrue(call.body.orEmpty().contains("\"agent_id\":\"agent-1\""))
            ok(
                """{"id":"sched-1","agent_id":"agent-1","message":{"messages":[{"content":"hi","role":"user"}]},"schedule":{"type":"once","scheduled_at":1.0}}""",
            )
        }
        val directory = IrohAdminRpcAgentDirectory(transport)
        val schedule = directory.createSchedule(
            agentId = "agent-1",
            params = ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "hi", role = "user")),
                schedule = ScheduleDefinition(type = "once", scheduledAt = 1.0),
            ),
        )
        assertEquals("sched-1", schedule.id)
        assertEquals("agent-1", schedule.agentId)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun request(text: String): MessageCreateRequest = MessageCreateRequest(
        messages = listOf(
            Json.encodeToJsonElement(MessageCreate.serializer(), MessageCreate(role = "user", content = JsonPrimitive(text), otid = "otid-1")),
        ),
        streaming = true,
    )

    /**
     * A turn under test: the conversation it belongs to plus its turn id
     * (run id derived), so frame builders take one reference instead of
     * loose conversation/turn/run strings.
     */
    private data class TurnRef(val conversationId: String, val turnId: String) {
        val runId: String get() = "run-$turnId"
    }

    private fun turnStarted(turn: TurnRef) = ServerFrame.TurnStarted(
        id = "frame-${turn.turnId}",
        ts = "2026-07-09T00:00:00Z",
        agentId = "agent-1",
        conversationId = turn.conversationId,
        turnId = turn.turnId,
        runId = turn.runId,
    )

    /** [tagged] carries the frame's own conversation_id (fanned-out shape). */
    private fun assistantDelta(
        turn: TurnRef,
        id: String,
        content: String,
        tagged: Boolean = false,
    ) = ServerFrame.AssistantMessage(
        id = id,
        ts = "2026-07-09T00:00:01Z",
        conversationId = turn.conversationId.takeIf { tagged },
        turnId = turn.turnId,
        runId = turn.runId,
        content = content,
    )

    private fun userMessage(turn: TurnRef, id: String, content: String) = ServerFrame.UserMessage(
        id = id,
        ts = "2026-07-09T00:00:00Z",
        agentId = "agent-1",
        conversationId = turn.conversationId,
        content = content,
        otid = "otid-$id",
    )

    private fun turnDone(turn: TurnRef, status: String) = ServerFrame.TurnDone(
        id = "frame-done-${turn.turnId}",
        ts = "2026-07-09T00:00:02Z",
        turnId = turn.turnId,
        runId = turn.runId,
        status = status,
    )

    private fun ok(resultJson: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req-1",
        success = true,
        result = Json.parseToJsonElement(resultJson),
    )

    private class FakeIrohTransport(
        private val sendAccepts: Boolean = true,
    ) : IChannelTransport {
        data class RpcCall(val method: String, val path: String, val body: String?)
        data class SendCall(val agentId: String, val conversationId: String, val text: String, val otid: String?)

        val rpcCalls = mutableListOf<RpcCall>()
        val sends = mutableListOf<SendCall>()
        var rpcResponder: (RpcCall) -> AppServerInboundFrame.AdminRpcResponse = { call ->
            AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = false, error = "${call.method} has no responder")
        }

        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()

        suspend fun emitFrame(frame: ServerFrame) = frameEvents.emit(TransportFrameEvent(frame))

        override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) = Unit
        override fun send(agentId: String, conversationId: String, text: String, otid: String?, contentParts: JsonArray?, startNewConversation: Boolean): Boolean {
            sends += SendCall(agentId, conversationId, text, otid)
            return sendAccepts
        }
        override fun cancel(conversationId: String): Boolean = true
        override fun bye(): Boolean = true
        override suspend fun disconnect() = Unit
        override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = A2uiActionDispatchResult.Sent("frame-1")
        override fun subscribe(runId: String, cursor: Long): Boolean = false
        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            val call = RpcCall(method, path, body)
            rpcCalls += call
            return rpcResponder(call)
        }
        override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronAdd(agentId: String, name: String, description: String, prompt: String, recurring: Boolean, cron: String?, every: String?, at: String?, timezone: String?, conversationId: String?, timeoutMs: Long) = error("unused")
        override suspend fun sendCronGet(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDelete(taskId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long) = error("unused")
        override suspend fun sendSubagentTodos(toolCallId: String, timeoutMs: Long) = error("unused")
    }
}
