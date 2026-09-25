package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** letta-mobile-ztuog test fixture: one turn of one agent, with the ids the wire derives from it. */
internal data class WireTurn(val agent: AgentId, val id: String) {
    val runId: String get() = "run-$id"
    val messageId: String get() = "msg-$id"
    val conversation: ConversationId get() = ConversationId("conv-${agent.value}")
}

/**
 * letta-mobile-ztuog test fixture: a connected transport shared by every chat under test, plus
 * builders for the frames of an observer turn (only turn_started and the message name the agent).
 */
internal class AgentScopeWire {
    private val transport = FrameTransport()
    val bridge = WsChatBridge(transport)

    suspend fun emit(frame: ServerFrame) = transport.frameEvents.emit(TransportFrameEvent(frame))

    suspend fun started(turn: WireTurn) = emit(
        ServerFrame.TurnStarted(
            id = "ts-${turn.id}", ts = "", agentId = turn.agent.value,
            conversationId = turn.conversation.value, turnId = turn.id, runId = turn.runId,
        ),
    )

    suspend fun assistant(turn: WireTurn) = emit(
        ServerFrame.AssistantMessage(
            id = turn.messageId, agentId = turn.agent.value, conversationId = turn.conversation.value,
            turnId = turn.id, runId = turn.runId, content = "from ${turn.agent.value}",
        ),
    )

    suspend fun stopReason(turn: WireTurn) =
        emit(ServerFrame.StopReason(turnId = turn.id, runId = turn.runId, stopReason = "end_turn"))

    suspend fun done(turn: WireTurn) = emit(
        ServerFrame.TurnDone(id = "d-${turn.id}", ts = "", turnId = turn.id, runId = turn.runId, status = "completed"),
    )

    /** started, assistant, stop reason, done. */
    suspend fun fullTurn(turn: WireTurn) {
        started(turn)
        assistant(turn)
        stopReason(turn)
        done(turn)
    }

    private class FrameTransport : NoOpChannelTransport() {
        override val state: StateFlow<ChannelTransportState> =
            MutableStateFlow(ChannelTransportState.Connected("server", "session", "device"))
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
    }
}
