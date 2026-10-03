package com.letta.mobile.web.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerAdminMethod
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTimelineConnection
import com.letta.mobile.data.transport.appserver.AppServerTimelineTransport
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.TimelineChatRunControls
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-o4ygk.4.5: the web's port against a fake App Server, the way desktop's port tests
 * drive DesktopChatSessionPort: the real App Server timeline transport, shared timeline loop and
 * presenter run; only the socket's answers are canned.
 */
class WebChatSessionPortTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = TimelineChatTarget(agentId = "agent-1", agentName = "Nora", conversationId = "conv-1")

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun hydratesTheConversationIntoTheSharedPageState() = runTest {
        val server = FakeAppServer(history = HISTORY)
        val port = WebChatSessionPort(WebChatBinding(target, server.transport(), TimelineChatRunControls()), scope)

        val state = awaitState(port) { it.conversationState is ConversationState.Ready && it.messages.size == 2 }

        assertEquals("Nora", state.agentName)
        assertEquals(listOf("hi", "hello there"), state.messages.map { it.content })
        port.close()
    }

    @Test
    fun aSendRunsATurnAndItsStreamedReplyLands() = runTest {
        val server = FakeAppServer(history = EMPTY_HISTORY, reply = REPLY_DELTA)
        val port = WebChatSessionPort(WebChatBinding(target, server.transport(), TimelineChatRunControls()), scope)
        awaitState(port) { it.conversationState is ConversationState.Ready }

        port.actions.updateComposerText("ping")
        port.actions.send()

        val state = awaitState(port) { ui -> ui.messages.any { it.content == "pong" } }
        assertTrue(state.messages.any { it.role == "user" && it.content == "ping" })
        assertEquals(1, server.turns.size)
        port.close()
    }

    @Test
    fun theTransportsControlsDecideWhatThePageOffers() = runTest {
        val answers = mutableListOf<ChatApprovalAnswer>()
        val transport = FakeAppServer(history = EMPTY_HISTORY).transport()
        val withControls = WebChatSessionPort(
            WebChatBinding(target, transport, TimelineChatRunControls(answerApproval = { answers += it })),
            scope,
        )
        val bare = WebChatSessionPort(WebChatBinding(target, transport, TimelineChatRunControls()), scope)

        assertTrue(withControls.capabilities.value.approvals)
        assertEquals(false, bare.capabilities.value.approvals)
        assertEquals(false, bare.capabilities.value.modelSwitch)
        withControls.close()
        bare.close()
    }

    private suspend fun awaitState(port: WebChatSessionPort, predicate: (ChatUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { port.uiState.first(predicate) } }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        val EMPTY_HISTORY: JsonElement = JsonArray(emptyList())
        val HISTORY: JsonElement = Json.parseToJsonElement(
            """[{"message_type":"user_message","id":"m-1","date":"2026-10-02T09:00:00Z","content":"hi"},""" +
                """{"message_type":"assistant_message","id":"m-2","date":"2026-10-02T09:00:01Z","content":"hello there"}]""",
        )
        const val REPLY_DELTA = """{"message_type":"assistant_message","id":"m-9","content":"pong"}"""
    }
}

/**
 * A canned App Server: `message.list` answers [history] (then the sent turn too, as the server
 * would), and a turn streams [reply] as one assistant frame.
 */
private class FakeAppServer(
    private val history: JsonElement,
    private val reply: String? = null,
) {
    val turns = mutableListOf<TurnCommand>()

    fun transport() = AppServerTimelineTransport(
        AppServerTimelineConnection(
            turnEngine = { command -> runTurn(command) },
            events = MutableSharedFlow<AppServerReceivedFrame>(),
            isConnected = flowOf(true),
            admin = { method, _ -> listing(method) },
            agentIdFor = { AgentId("agent-1") },
        ),
    )

    private fun listing(method: AppServerAdminMethod): JsonElement {
        check(method.value == "message.list") { "unexpected ${method.value}" }
        return if (turns.isEmpty()) history else Json.parseToJsonElement(
            """[{"message_type":"user_message","id":"u-1","date":"2026-10-02T09:01:00Z","content":"ping"},""" +
                """{"message_type":"assistant_message","id":"m-9","date":"2026-10-02T09:01:01Z","content":"pong"}]""",
        )
    }

    private fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
        turns += command
        val frame = reply ?: return flowOf()
        return flowOf(
            RuntimeEventDraft(
                backendId = BackendId("test"),
                runtimeId = RuntimeId("test:conv-1"),
                agentId = command.agentId,
                conversationId = command.conversationId,
                source = RuntimeEventSource.RemoteLetta,
                payload = RuntimeEventPayload.RemoteStreamFrame(frameId = "f-1", messageType = "assistant_message", body = frame),
            ),
        )
    }
}
