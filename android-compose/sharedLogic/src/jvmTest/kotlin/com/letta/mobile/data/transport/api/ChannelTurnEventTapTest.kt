package com.letta.mobile.data.transport.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.presence.ActiveTurnPhase
import com.letta.mobile.data.presence.ActiveTurnRuntimeEvent
import com.letta.mobile.data.presence.ActiveTurnSession
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.RunId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelTurnEventTapTest {
    @Test
    fun terminalDuringGraphHandoffIsFencedBeforeRestoredStart() = runTest {
        val old = ActiveTurnSession(backgroundScope)
        val next = ActiveTurnSession(backgroundScope)
        val tap = ChannelTurnEventTap(logicalRun = { RunId(it.turnId) }, abort = { _, _, _ -> error("No action replay") })
        fun attach(session: ActiveTurnSession) = tap.observeTurns { draft, logical, server ->
            session.onEvent(ActiveTurnRuntimeEvent(ConversationId(checkNotNull(draft.conversationId).value),
                checkNotNull(draft.agentId), draft.payload, logical, server))
        }
        val detachOld = attach(old)
        val start = ServerFrame.TurnStarted(id = "start", ts = "", agentId = "agent", conversationId = "conversation",
            turnId = "turn", runId = "server")
        tap.publish(start)
        val detachNext = attach(next) // Providers construct the next graph before closing the previous graph.
        old.close()
        detachOld()
        tap.publish(ServerFrame.TurnDone(id = "done", ts = "", turnId = "turn", runId = "server", status = "completed"))
        tap.publish(start)
        assertEquals(ActiveTurnPhase.Idle, next.owner(ConversationId("conversation"), AgentId("agent")).state.value.phase)
        detachNext()
        next.close()
    }

    @Test
    fun terminalBeforePresentationFanoutAndLateOldFramesCannotLeaveBusy() = runTest {
        val session = ActiveTurnSession(backgroundScope)
        val tap = ChannelTurnEventTap(logicalRun = { RunId(it.turnId) }, abort = { _, _, _ ->
            error("Observation and restoration must not dispatch abort")
        })
        val detach = tap.observeTurns { draft, logical, server ->
            session.onEvent(ActiveTurnRuntimeEvent(ConversationId(checkNotNull(draft.conversationId).value),
                checkNotNull(draft.agentId), draft.payload, logical, server))
        }
        val owner = session.owner(ConversationId("conversation"), AgentId("agent"))
        tap.publish(ServerFrame.TurnStarted(id = "start", ts = "", agentId = "agent", conversationId = "conversation",
            turnId = "turn-one", runId = "server-one"))
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        // The terminal is consumed before the presentation fan-out; no UI frame collector is attached.
        tap.publish(ServerFrame.TurnDone(id = "done", ts = "", turnId = "turn-one", runId = "server-one", status = "completed"))
        assertEquals(ActiveTurnPhase.Idle, owner.state.value.phase)
        tap.publish(ServerFrame.TurnStarted(id = "next", ts = "", agentId = "agent", conversationId = "conversation",
            turnId = "turn-two", runId = "server-two"))
        tap.publish(ServerFrame.TurnDone(id = "late", ts = "", turnId = "turn-one", runId = "server-one", status = "completed"))
        assertEquals("turn-two", owner.state.value.runId)
        assertEquals(ActiveTurnPhase.Thinking, owner.state.value.phase)
        detach()
        session.close()
    }
}
