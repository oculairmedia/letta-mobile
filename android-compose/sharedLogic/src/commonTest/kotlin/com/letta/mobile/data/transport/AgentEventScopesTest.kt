package com.letta.mobile.data.transport

import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ztuog: one shared bridge, per-agent delivery. A frame for agent A must never reach
 * agent B's subscription — including the frames that do not name their agent (stop_reason,
 * turn_done), which follow the turn that named it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentEventScopesTest {
    @Test
    fun eachAgentReceivesOnlyItsOwnFramesIncludingUntaggedTerminals() = runTest {
        val wire = Wire()
        val a = wire.collect(backgroundScope) { wire.bridge.agentScopes.eventsFor(AGENT_A) }
        val b = wire.collect(backgroundScope) { wire.bridge.agentScopes.eventsFor(AGENT_B) }
        runCurrent()

        wire.turnStarted(AGENT_A, "turn-a")
        wire.turnStarted(AGENT_B, "turn-b")
        wire.assistant(AGENT_A, "turn-a", "msg-a")
        wire.assistant(AGENT_B, "turn-b", "msg-b")
        wire.emit(ServerFrame.StopReason(turnId = "turn-a", runId = "run-turn-a", stopReason = "end_turn"))
        wire.turnDone("turn-b")
        wire.turnDone("turn-a")
        runCurrent()

        assertEquals(listOf("TurnStarted:turn-a", "MessageDelta:msg-a", "StopReason:turn-a", "TurnDone:turn-a"), a.labels())
        assertEquals(listOf("TurnStarted:turn-b", "MessageDelta:msg-b", "TurnDone:turn-b"), b.labels())
    }

    @Test
    fun unattributableFramesStillReachEveryAgent() = runTest {
        val wire = Wire()
        val a = wire.collect(backgroundScope) { wire.bridge.agentScopes.eventsFor(AGENT_A) }
        val b = wire.collect(backgroundScope) { wire.bridge.agentScopes.eventsFor(AGENT_B) }
        runCurrent()

        // A bare `default` conversation claimed by two agents is ambiguous: nobody owns it.
        wire.bridge.send(AGENT_A, "default", "hi")
        wire.bridge.send(AGENT_B, "default", "hi")
        wire.emit(ServerFrame.Error(id = "e", ts = "", code = "busy", message = "busy", conversationId = "default"))
        runCurrent()

        assertEquals(listOf("Error:default"), a.labels())
        assertEquals(listOf("Error:default"), b.labels())
    }

    @Test
    fun deselectedIdleChatStopsReceivingAndReselectionReattaches() = runTest {
        val wire = Wire()
        val scopes = wire.bridge.agentScopes
        val attachA = scopes.attachment(AGENT_A, isBusy = { false })
        val attachB = scopes.attachment(AGENT_B, isBusy = { false })
        val a = wire.collect(backgroundScope) { attachA.deliveries }
        val b = wire.collect(backgroundScope) { attachB.deliveries }
        runCurrent()
        assertEquals(1, scopes.liveSubscriberCount(AGENT_B))

        attachA.select()
        wire.turnStarted(AGENT_B, "turn-b1")
        runCurrent()
        assertEquals(emptyList(), b.labels(), "an unselected, idle chat receives nothing")
        assertEquals(0, scopes.liveSubscriberCount(AGENT_B), "and holds no subscription")

        attachB.select()
        runCurrent()
        wire.turnStarted(AGENT_B, "turn-b2")
        wire.turnStarted(AGENT_A, "turn-a1")
        runCurrent()
        assertEquals(listOf("TurnStarted:turn-b2"), b.labels(), "re-selection re-attaches")
        assertEquals(emptyList(), a.labels(), "the chat it replaced detached")
        assertEquals(0, scopes.liveSubscriberCount(AGENT_A))
    }

    @Test
    fun unselectedChatWithTurnInFlightStaysAttachedUntilItSettles() = runTest {
        val wire = Wire()
        val scopes = wire.bridge.agentScopes
        val b = Received()
        // Busy exactly like a coordinator: from its turn's start until that turn's terminal is handled.
        val attachB = scopes.attachment(AGENT_B, isBusy = { b.events.none { it is WsTimelineEvent.TurnDone } })
        val attachA = scopes.attachment(AGENT_A, isBusy = { false })
        backgroundScope.launch { attachB.deliveries.collect { b.events += it } }
        runCurrent()
        wire.turnStarted(AGENT_B, "turn-b")
        runCurrent()

        attachA.select()
        wire.assistant(AGENT_B, "turn-b", "msg-b")
        wire.turnDone("turn-b")
        wire.turnStarted(AGENT_B, "turn-b-next")
        runCurrent()

        assertEquals(listOf("TurnStarted:turn-b", "MessageDelta:msg-b", "TurnDone:turn-b"), b.labels())
        assertEquals(0, scopes.liveSubscriberCount(AGENT_B), "settled and unselected: detached")
    }

    private class Wire {
        val transport = FrameTransport()
        val bridge = WsChatBridge(transport)

        suspend fun emit(frame: ServerFrame) = transport.frameEvents.emit(TransportFrameEvent(frame))

        suspend fun turnStarted(agentId: String, turnId: String) = emit(
            ServerFrame.TurnStarted(
                id = "ts-$turnId", ts = "", agentId = agentId, conversationId = "conv-$agentId",
                turnId = turnId, runId = "run-$turnId",
            ),
        )

        suspend fun assistant(agentId: String, turnId: String, id: String) = emit(
            ServerFrame.AssistantMessage(
                id = id, agentId = agentId, conversationId = "conv-$agentId",
                turnId = turnId, runId = "run-$turnId", content = "from $agentId",
            ),
        )

        suspend fun turnDone(turnId: String) = emit(
            ServerFrame.TurnDone(id = "d-$turnId", ts = "", turnId = turnId, runId = "run-$turnId", status = "completed"),
        )

        fun collect(scope: CoroutineScope, source: () -> Flow<WsTimelineEvent>): Received {
            val received = Received()
            scope.launch { source().collect { received.events += it } }
            return received
        }
    }

    private class Received {
        val events = mutableListOf<WsTimelineEvent>()

        fun labels(): List<String> = events.map { it.label() }

        private fun WsTimelineEvent.label(): String = when (this) {
            is WsTimelineEvent.TurnStarted -> "TurnStarted:$turnId"
            is WsTimelineEvent.MessageDelta -> "MessageDelta:${message.id}"
            is WsTimelineEvent.StopReason -> "StopReason:$turnId"
            is WsTimelineEvent.TurnDone -> "TurnDone:$turnId"
            is WsTimelineEvent.Error -> "Error:$conversationId"
            else -> this::class.simpleName.orEmpty()
        }
    }

    private class FrameTransport : NoOpChannelTransport() {
        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()

        override fun send(
            agentId: String,
            conversationId: String,
            text: String,
            otid: String?,
            contentParts: JsonArray?,
            startNewConversation: Boolean,
        ): Boolean = true
    }

    private companion object {
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
    }
}
