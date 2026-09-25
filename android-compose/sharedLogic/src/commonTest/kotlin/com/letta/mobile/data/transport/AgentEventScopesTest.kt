package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ztuog: one shared bridge, per-agent delivery. A frame for agent A must never reach
 * agent B's subscription — including the frames that do not name their agent (stop_reason,
 * turn_done), which follow the turn that named it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentEventScopesTest {
    private val wire = AgentScopeWire()
    private val scopes = wire.bridge.agentScopes

    @Test
    fun eachAgentReceivesOnlyItsOwnFramesIncludingUntaggedTerminals() = runTest {
        val a = collect(backgroundScope) { scopes.eventsFor(AGENT_A) }
        val b = collect(backgroundScope) { scopes.eventsFor(AGENT_B) }
        runCurrent()

        val turnA = WireTurn(AGENT_A, "turn-a")
        val turnB = WireTurn(AGENT_B, "turn-b")
        wire.started(turnA)
        wire.started(turnB)
        wire.assistant(turnA)
        wire.assistant(turnB)
        wire.stopReason(turnA)
        wire.done(turnB)
        wire.done(turnA)
        runCurrent()

        assertEquals(listOf("TurnStarted:turn-a", "MessageDelta:msg-turn-a", "StopReason:turn-a", "TurnDone:turn-a"), a.labels())
        assertEquals(listOf("TurnStarted:turn-b", "MessageDelta:msg-turn-b", "TurnDone:turn-b"), b.labels())
    }

    @Test
    fun unattributableFramesStillReachEveryAgent() = runTest {
        val a = collect(backgroundScope) { scopes.eventsFor(AGENT_A) }
        val b = collect(backgroundScope) { scopes.eventsFor(AGENT_B) }
        runCurrent()

        // A bare `default` conversation claimed by two agents is ambiguous: nobody owns it.
        wire.bridge.send(AGENT_A.value, "default", "hi")
        wire.bridge.send(AGENT_B.value, "default", "hi")
        wire.emit(ServerFrame.Error(id = "e", ts = "", code = "busy", message = "busy", conversationId = "default"))
        runCurrent()

        assertEquals(listOf("Error:default"), a.labels())
        assertEquals(listOf("Error:default"), b.labels())
    }

    @Test
    fun deselectedIdleChatStopsReceivingAndReselectionReattaches() = runTest {
        val attachA = scopes.attachment(AGENT_A) { false }
        val attachB = scopes.attachment(AGENT_B) { false }
        val a = collect(backgroundScope) { attachA.deliveries }
        val b = collect(backgroundScope) { attachB.deliveries }
        runCurrent()
        assertEquals(1, scopes.liveSubscriberCount(AGENT_B))

        attachA.select()
        wire.started(WireTurn(AGENT_B, "turn-b1"))
        runCurrent()
        assertEquals(emptyList(), b.labels(), "an unselected, idle chat receives nothing")
        assertEquals(0, scopes.liveSubscriberCount(AGENT_B), "and holds no subscription")

        attachB.select()
        runCurrent()
        wire.started(WireTurn(AGENT_B, "turn-b2"))
        wire.started(WireTurn(AGENT_A, "turn-a1"))
        runCurrent()
        assertEquals(listOf("TurnStarted:turn-b2"), b.labels(), "re-selection re-attaches")
        assertEquals(emptyList(), a.labels(), "the chat it replaced detached")
        assertEquals(0, scopes.liveSubscriberCount(AGENT_A))
    }

    @Test
    fun unselectedChatWithTurnInFlightStaysAttachedUntilItSettles() = runTest {
        val b = Received()
        // Busy exactly like a coordinator: from its turn's start until that turn's terminal is handled.
        val attachB = scopes.attachment(AGENT_B) { b.events.none { it is WsTimelineEvent.TurnDone } }
        val attachA = scopes.attachment(AGENT_A) { false }
        backgroundScope.launch { attachB.deliveries.collect { b.events += it } }
        runCurrent()
        val turnB = WireTurn(AGENT_B, "turn-b")
        wire.started(turnB)
        runCurrent()

        attachA.select()
        wire.assistant(turnB)
        wire.done(turnB)
        wire.started(WireTurn(AGENT_B, "turn-b-next"))
        runCurrent()

        assertEquals(listOf("TurnStarted:turn-b", "MessageDelta:msg-turn-b", "TurnDone:turn-b"), b.labels())
        assertEquals(0, scopes.liveSubscriberCount(AGENT_B), "settled and unselected: detached")
    }

    private fun collect(scope: CoroutineScope, source: () -> Flow<WsTimelineEvent>): Received {
        val received = Received()
        scope.launch { source().collect { received.events += it } }
        return received
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

    private companion object {
        val AGENT_A = AgentId("agent-a")
        val AGENT_B = AgentId("agent-b")
    }
}
