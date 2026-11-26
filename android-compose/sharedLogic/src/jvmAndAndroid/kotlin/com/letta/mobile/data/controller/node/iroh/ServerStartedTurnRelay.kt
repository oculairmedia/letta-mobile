package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.RunId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * Relays the turns the App Server starts by itself (a background task finishing after the client's turn closed, a
 * wake, a cron run, an inbox arrival) the way a client turn is relayed: each is opened, journaled and closed in its
 * conversation's feed through a [ConversationTurnFanout] with no initiator, and fanned out to the viewers [delivery]
 * names.
 *
 * Every frame is keyed on its own runtime scope. A turn opens on a stream delta no client turn answers for
 * ([NodeTurnHost.clientOwns]) and closes on the server's `turn_finished`. A turn whose `turn_finished` was lost
 * closes (`terminal_observed` false) when it had no frame for [SILENCE_MS], when the App Server connection generation
 * changed under it, or when `update_loop_status` binds the active run to a running client turn's prompt.
 *
 * Each raw frame is relayed by exactly one turn: this relay and a client turn's fanout both claim it through
 * [NodeTurnHost.claimFrame] and the loser skips it. All of this relay's state belongs to its one collector.
 */
internal class ServerStartedTurnRelay(
    private val host: NodeTurnHost,
    private val delivery: TurnFanoutDelivery,
    private val nowMs: () -> Long,
) {
    private class OpenTurn(val turn: NodeTurn, val generation: Long?, var seenAtMs: Long)

    private val open = HashMap<AppServerRuntimeScope, OpenTurn>()

    /** Relays [frames] (every runtime-scoped App Server frame) until they end or the collector is cancelled. */
    suspend fun relay(frames: Flow<AppServerReceivedFrame>) {
        try {
            frames.collect { received -> runCatching { onFrame(received) }.onFailure(::noteFailure) }
        } finally {
            open.keys.toList().forEach { close(it, terminalObserved = false, why = "relay_stopped") }
        }
    }

    private suspend fun onFrame(received: AppServerReceivedFrame) {
        val frame = received.frame
        val key = frame.runtime?.let { AppServerRuntimeScope(it.agentId, it.conversationId) } ?: return
        closeIfLost(key, received.connectionGeneration)
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> onDelta(key, frame, received)
            is AppServerInboundFrame.TurnFinished -> close(key, terminalObserved = true, why = frame.stopReason)
            is AppServerInboundFrame.UpdateLoopStatus -> onLoopStatus(key, frame.loopStatus)
            else -> Unit
        }
    }

    private suspend fun onDelta(key: AppServerRuntimeScope, frame: AppServerInboundFrame.StreamDelta, received: AppServerReceivedFrame) {
        if (frame.subagentId != null) return
        val delta = frame.delta as? JsonObject ?: return
        val run = delta.runOrNull()
        val current = open[key] ?: run {
            if (host.clientOwns(ConversationId(key.conversationId), run)) return
            openTurn(key, received.connectionGeneration)
        }
        current.seenAtMs = nowMs()
        if (!host.claimFrame(frame.idempotencyKey, run, server = true)) return
        current.turn.fanout.relayServerStartedDelta(received.raw)
    }

    /** The active run is a running client turn's: it is that turn's, whatever this relay assumed so far. */
    private fun onLoopStatus(key: AppServerRuntimeScope, status: AppServerLoopStatus) {
        val clientRuns = status.activeRunIds.filter { run ->
            host.clientSent(ConversationId(key.conversationId), status.clientMessageIdsByRunId[run].orEmpty())
        }
        if (clientRuns.isEmpty()) return
        host.releaseServerRuns(clientRuns.filter { it.isNotBlank() }.map(::RunId))
        if (key in open) close(key, terminalObserved = false, why = "client_run_bound")
    }

    private fun closeIfLost(key: AppServerRuntimeScope, generation: Long?) {
        val current = open[key] ?: return
        val generationChanged = current.generation != null && generation != null && current.generation != generation
        val silent = nowMs() - current.seenAtMs >= SILENCE_MS
        if (generationChanged || silent) {
            close(key, terminalObserved = false, why = if (generationChanged) "generation_changed" else "silent")
        }
    }

    private fun openTurn(runtime: AppServerRuntimeScope, generation: Long?): OpenTurn {
        val turnId = "turn-${UUID.randomUUID()}"
        val writer = host.journal.openTurn(AgentId(runtime.agentId), ConversationId(runtime.conversationId), turnId)
        val fanout = ConversationTurnFanout(
            conversationId = runtime.conversationId,
            runtime = runtime,
            delivery = delivery,
            initiatorViewer = null,
            trackInitiatorFrame = writer::append,
            turnId = turnId,
        )
        val opened = OpenTurn(NodeTurn(runtime.conversationId, clientMessageId = null, fanout, writer) {}, generation, nowMs())
        open[runtime] = opened
        Telemetry.event("IrohNode", "turn.server_started_opened", "conversationId" attr runtime.conversationId, "turnId" attr turnId)
        return opened
    }

    private fun close(key: AppServerRuntimeScope, terminalObserved: Boolean, why: String) {
        val closed = open.remove(key)?.turn ?: return
        closed.journal.close(terminalObserved = terminalObserved || closed.fanout.anyTerminalWritten)
        Telemetry.event(
            "IrohNode", "turn.server_started_closed",
            "conversationId" attr key.conversationId,
            "terminalObserved" attr terminalObserved,
            "reason" attr why,
        )
    }

    /** One bad frame must not stop the relay for every conversation. */
    private fun noteFailure(error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Telemetry.event(
            "IrohNode", "turn.server_started_frame_failed",
            "class" attr error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
    }

    private fun JsonObject.runOrNull(): RunId? =
        (this["run_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::RunId)

    companion object {
        /** A server-started turn with no frame for this long lost its `turn_finished`: the engine's turn idle bound. */
        const val SILENCE_MS = 300_000L
    }
}
