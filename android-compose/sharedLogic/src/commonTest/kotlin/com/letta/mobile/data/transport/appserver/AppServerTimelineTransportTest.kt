package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** letta-mobile-o4ygk.4.5: the App Server timeline transport the web's chat page runs on. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTimelineTransportTest {
    @Test
    fun listsMessagesOverAdminRpcWithTheConversationsCursors() = runTest {
        val calls = mutableListOf<Pair<AppServerAdminMethod, JsonObject>>()
        val transport = transport(admin = { method, params ->
            calls += method to params
            Json.parseToJsonElement("""{"messages":[$ASSISTANT_ROW]}""")
        })

        val messages = transport.listConversationMessages("conv-1", limit = 20, after = null, order = "asc")
        val older = transport.list(ConversationId("conv-1"), AppServerMessagePage(limit = 10, before = "msg-9", order = "desc"))

        assertEquals("hello", (messages.single() as AssistantMessage).content)
        assertEquals(1, older.size)
        val (method, params) = calls.first()
        assertEquals("message.list", method.value)
        assertEquals("conv-1", params["conversation_id"]?.jsonPrimitive?.content)
        assertEquals("20", params["limit"]?.jsonPrimitive?.content)
        assertEquals("asc", params["order"]?.jsonPrimitive?.content)
        assertEquals("msg-9", calls.last().second["before"]?.jsonPrimitive?.content)
    }

    @Test
    fun aBareArrayAnswerDecodesToo() = runTest {
        val transport = transport(admin = { _, _ -> Json.parseToJsonElement("[$ASSISTANT_ROW]") })

        assertEquals(1, transport.listConversationMessages("conv-1").size)
    }

    @Test
    fun aFailedListIsATransportFailure() = runTest {
        val transport = transport(admin = { _, _ -> error("message.list timed out") })

        assertFailsWith<TimelineTransportHttpException> { transport.listConversationMessages("conv-1") }
    }

    @Test
    fun theStreamCarriesThisConversationsFramesOnly() = runTest {
        val events = MutableSharedFlow<AppServerReceivedFrame>()
        val transport = transport(events = events)
        val received = mutableListOf<TimelineStreamFrame>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.streamConversation("conv-1").collect { received += it }
        }
        runCurrent()

        events.emit(streamDelta(ConversationId("conv-other")))
        events.emit(streamDelta(ConversationId("conv-1")))
        runCurrent()
        collector.cancel()

        val message = assertIs<TimelineStreamFrame.Message>(received.single()).message
        assertEquals("hello", (message as AssistantMessage).content)
    }

    @Test
    fun aSendMapsTheTurnsReplyToMessages() = runTest {
        val transport = transport(turns = { command ->
            flowOf(draft(command, RuntimeEventPayload.RemoteStreamFrame(frameId = "frame-1", messageType = "assistant_message", body = ASSISTANT_DELTA)))
        })

        val messages = transport.sendConversationMessage("conv-1", MessageCreateRequest(input = "hi")).toList()

        assertTrue(messages.any { it is AssistantMessage && it.content == "hello" })
    }

    @Test
    fun aFailedTurnShowsItsNoticeAndFailsTheSend() = runTest {
        val transport = transport(turns = { command ->
            flowOf(draft(command, RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Failed, reason = "model unavailable")))
        })
        val emitted = mutableListOf<com.letta.mobile.data.model.LettaMessage>()

        assertFailsWith<TimelineTransportHttpException> {
            transport.sendConversationMessage("conv-1", MessageCreateRequest(input = "hi")).collect { emitted += it }
        }
        assertIs<ErrorMessage>(emitted.single())
    }

    private fun transport(
        admin: AppServerAdminCall = AppServerAdminCall { _, _ -> null },
        events: Flow<AppServerReceivedFrame> = MutableSharedFlow(),
        turns: (TurnCommand) -> Flow<RuntimeEventDraft> = { flowOf() },
    ) = AppServerTimelineTransport(
        AppServerTimelineConnection(
            turnEngine = { command -> turns(command) },
            events = events,
            isConnected = flowOf(true),
            admin = admin,
            agentIdFor = { AgentId("agent-1") },
            heartbeatInterval = 60.seconds,
        ),
    )

    private fun draft(command: TurnCommand, payload: RuntimeEventPayload) = RuntimeEventDraft(
        backendId = BackendId("test"),
        runtimeId = RuntimeId("test:conv-1"),
        agentId = command.agentId,
        conversationId = command.conversationId,
        source = RuntimeEventSource.RemoteLetta,
        payload = payload,
    )

    /** A `stream_delta` decoded the way the socket transport decodes it, raw frame included. */
    private fun streamDelta(conversation: ConversationId): AppServerReceivedFrame = AppServerProtocol.decodeFrame(
        """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"${conversation.value}"},""" +
            """"event_seq":1,"emitted_at":"2026-10-02T00:00:00Z","idempotency_key":"frame-${conversation.value}",""" +
            """"delta":$ASSISTANT_DELTA}""",
        AppServerChannel.Stream,
    )

    private companion object {
        const val ASSISTANT_ROW = """{"message_type":"assistant_message","id":"msg-1","content":"hello","date":"2026-10-02T00:00:00Z"}"""
        const val ASSISTANT_DELTA = """{"message_type":"assistant_message","id":"msg-1","content":"hello"}"""
    }
}
