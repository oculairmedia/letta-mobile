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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
 * ([NodeTurnHost.clientOwns]) and closes on the server's `turn_finished`. A later frame on a run the turn already relayed
 * keeps it open however long it was quiet, so one run is never split across two turns. A turn whose `turn_finished`
 * was lost closes (`terminal_observed` false) when the App Server connection generation changed under it, when
 * `update_loop_status` binds the active run to a running client turn's prompt, or once it has shown no sign of life
 * for [SERVER_TURN_SILENCE_MS] while the server was not running a client-side tool or awaiting an approval. That last
 * check also runs on a timer in the relay's own collector, so it needs no further frame.
 *
 * Each raw frame is relayed by exactly one turn: this relay and a client turn's fanout both claim it through
 * [NodeTurnHost.claimFrame] and the loser skips it. All of this relay's state belongs to its one collector.
 */
internal class ServerStartedTurnRelay(
    private val host: NodeTurnHost,
    private val delivery: TurnFanoutDelivery,
    private val nowMs: () -> Long,
) {
    /**
     * Open turns per runtime, with when each last showed it was alive, the App Server generation it began on, its runs,
     * and whether the server last said it is waiting on a tool or an approval. Collector-only.
     */
    private val open = HashMap<AppServerRuntimeScope, NodeTurn>()
    private val seenAtMs = HashMap<AppServerRuntimeScope, Long>()
    private val generations = HashMap<AppServerRuntimeScope, Long>()
    private val runs = HashMap<AppServerRuntimeScope, MutableSet<RunId>>()
    private val busy = HashSet<AppServerRuntimeScope>()

    /**
     * Relays [frames] (every runtime-scoped App Server frame) until they end or the collector is cancelled. [ticks]
     * runs the silence check without a frame: each tick closes the turns that are silent, on this same collector.
     */
    suspend fun relay(frames: Flow<AppServerReceivedFrame>, ticks: Flow<Unit> = silenceTicks()) {
        try {
            // A null is a silence check: both reach this one collector, so no state is shared with another coroutine.
            merge<AppServerReceivedFrame?>(frames, ticks.map { null }).collect { received ->
                runCatching { if (received == null) closeSilent() else onFrame(received) }.onFailure(::noteFailure)
            }
        } finally {
            open.keys.toList().forEach { close(it, terminalObserved = false, why = "relay_stopped") }
        }
    }

    private fun closeSilent() {
        open.keys.filter(::isSilent).forEach { close(it, terminalObserved = false, why = "silent") }
    }

    private suspend fun onFrame(received: AppServerReceivedFrame) {
        val frame = received.frame
        val key = frame.runtime?.let { AppServerRuntimeScope(it.agentId, it.conversationId) } ?: return
        closeIfLost(key, received)
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
        seenAtMs[key] = nowMs()
        run?.let { runs.getOrPut(key) { HashSet() } += it }
        if (!host.claimFrame(frame.idempotencyKey, run, server = true)) return
        current.fanout.relayServerStartedDelta(received.raw)
    }

    /** The active run is a running client turn's: it is that turn's, whatever this relay assumed so far. */
    private fun onLoopStatus(key: AppServerRuntimeScope, status: AppServerLoopStatus) {
        if (key in open) noteLoopStatus(key, status)
        val clientRuns = status.activeRunIds.filter { run ->
            host.clientSent(ConversationId(key.conversationId), status.clientMessageIdsByRunId[run].orEmpty())
        }
        if (clientRuns.isEmpty()) return
        host.releaseServerRuns(clientRuns.filter { it.isNotBlank() }.map(::RunId))
        if (key in open) close(key, terminalObserved = false, why = "client_run_bound")
    }

    /** The server is running a tool or waiting on an approval for this turn: a long quiet stretch is expected then. */
    private fun noteLoopStatus(key: AppServerRuntimeScope, status: AppServerLoopStatus) {
        if (status.status in WAITING_STATUSES) busy += key else busy -= key
        if (status.activeRunIds.any { it.isNotBlank() && RunId(it) in runs[key].orEmpty() }) seenAtMs[key] = nowMs()
    }

    /**
     * A frame on a run this turn already relayed proves the turn is alive, however long it was quiet. Otherwise a
     * generation change, or silence past the bound while the server was not running a tool or waiting on an approval,
     * means its `turn_finished` was lost.
     */
    private fun closeIfLost(key: AppServerRuntimeScope, received: AppServerReceivedFrame) {
        if (key !in open) return
        val began = generations[key]
        val generation = received.connectionGeneration
        val generationChanged = began != null && generation != null && began != generation
        if (generationChanged) {
            close(key, terminalObserved = false, why = "generation_changed")
            return
        }
        if (received.ownRunOf(key)) {
            seenAtMs[key] = nowMs()
            return
        }
        if (isSilent(key)) close(key, terminalObserved = false, why = "silent")
    }

    private fun isSilent(key: AppServerRuntimeScope): Boolean =
        key !in busy && nowMs() - (seenAtMs[key] ?: 0L) >= SERVER_TURN_SILENCE_MS

    private fun AppServerReceivedFrame.ownRunOf(key: AppServerRuntimeScope): Boolean {
        val run = when (val f = frame) {
            is AppServerInboundFrame.StreamDelta -> (f.delta as? JsonObject)?.runOrNull()
            is AppServerInboundFrame.TurnFinished -> f.runId?.takeIf { it.isNotBlank() }?.let(::RunId)
            else -> null
        }
        return run != null && run in runs[key].orEmpty()
    }

    private fun openTurn(runtime: AppServerRuntimeScope, generation: Long?): NodeTurn {
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
        val opened = NodeTurn(runtime.conversationId, clientMessageId = null, fanout, writer) {}
        open[runtime] = opened
        seenAtMs[runtime] = nowMs()
        generation?.let { generations[runtime] = it }
        Telemetry.event("IrohNode", "turn.server_started_opened", "conversationId" attr runtime.conversationId, "turnId" attr turnId)
        return opened
    }

    private fun close(key: AppServerRuntimeScope, terminalObserved: Boolean, why: String) {
        val closed = open.remove(key) ?: return
        seenAtMs.remove(key)
        generations.remove(key)
        runs.remove(key)
        busy.remove(key)
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
}

/** A silence check every [SILENCE_CHECK_MS], so a silent turn closes without waiting for another frame. */
private fun silenceTicks(): Flow<Unit> = flow {
    while (true) {
        delay(SILENCE_CHECK_MS)
        emit(Unit)
    }
}

/** Loop statuses during which the server is legitimately quiet: a client-side tool runs or an approval is awaited. */
private val WAITING_STATUSES = setOf("EXECUTING_CLIENT_SIDE_TOOL", "WAITING_ON_APPROVAL")

/** How often a silent turn is looked for. */
private const val SILENCE_CHECK_MS = 30_000L

/** A server-started turn with no frame for this long lost its `turn_finished`: the engine's turn idle bound. */
internal const val SERVER_TURN_SILENCE_MS = 300_000L
