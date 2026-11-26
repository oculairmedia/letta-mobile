package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The node-owned home of every live Iroh turn.
 *
 * A turn used to run as a child of its initiator's connection scope, so the phone's Iroh link
 * dropping mid-turn cancelled the `runTurn` collector. The engine then released its lease with
 * no terminal while the App Server kept running the turn unobserved: its approvals sat
 * unanswered, its external tools got synthesized errors, and the conversation queue wedged.
 *
 * The collector now runs here, under a [SupervisorJob] owned by the node (the endpoint), not by
 * any connection. The connection only awaits it. When the connection goes away the turn is
 * DETACHED instead of cancelled: delivery to the dead initiator stops, and everything else keeps
 * going until the engine reaches a terminal (or its 300s idle watchdog fires): auto-approve and
 * external-tool answering, fanout to the remaining viewers, and recording in the [journal] for
 * the initiator's redial replay.
 */
class NodeTurnHost internal constructor(
    internal val scope: CoroutineScope,
    /** The durable record of every turn this host runs. */
    val journal: TurnJournal = NoTurnJournal,
) {
    /** Serialized writes to observer viewers; node-owned so a detached turn still reaches them. */
    internal val observerWrites = ObserverWriteQueue(scope)

    private val live = AtomicInteger(0)

    /** Turns currently running on this host, attached or detached (telemetry/tests). */
    val liveTurnCount: Int get() = live.get()

    /** Client turns running per conversation; a server-started turn is never opened beside one. */
    private val clientTurns = ConcurrentHashMap<String, AtomicInteger>()

    /** The runs each conversation's finished client turns relayed, so their late frames open no turn. */
    private val clientRuns = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Whether a client turn answers for [runId] on [conversationId]: one is running there, or a finished one
     * relayed that run. Frames it does not answer for belong to a turn the App Server started itself.
     */
    internal fun clientOwns(conversationId: String, runId: String?): Boolean =
        (clientTurns[conversationId]?.get() ?: 0) > 0 || (runId != null && clientRuns[conversationId]?.contains(runId) == true)

    private fun noteClientTurnEnded(turn: NodeTurn) {
        clientTurns[turn.conversationId]?.decrementAndGet()
        val runs = clientRuns.computeIfAbsent(turn.conversationId) { java.util.Collections.synchronizedSet(LinkedHashSet()) }
        synchronized(runs) {
            runs += turn.fanout.relayedRunIds
            while (runs.size > REMEMBERED_CLIENT_RUNS) runs.remove(runs.first())
        }
    }

    /** Each conversation's relayed turn whose run is bound and not finished. */
    private val runningTurns = ConcurrentHashMap<String, IrohRelayedTurnProtocol>()

    internal fun markRunning(protocol: IrohRelayedTurnProtocol) {
        runningTurns[protocol.runtime.conversationId] = protocol
    }

    /** Clears [protocol]'s entry only: a newer turn of the same conversation keeps its own. */
    internal fun markIdle(protocol: IrohRelayedTurnProtocol) {
        runningTurns.remove(protocol.runtime.conversationId, protocol)
    }

    /**
     * Tells [viewer], which just started viewing [conversation], about the turn running there:
     * the same `update_loop_status` (active_run_ids, client_message_ids_by_run_id) its initiator got.
     */
    internal suspend fun replayLoopStatus(conversation: ConversationId, viewer: ViewerHandle): Boolean =
        runningTurns[conversation.value]?.replayLoopStatusTo(viewer) ?: false

    /**
     * Runs [turn] on the node scope and waits for it. If the caller (the initiator's connection)
     * is cancelled first, the turn keeps running detached and this rethrows the cancellation.
     */
    internal suspend fun run(turn: NodeTurn) {
        live.incrementAndGet()
        clientTurns.computeIfAbsent(turn.conversationId) { AtomicInteger() }.incrementAndGet()
        val job = scope.launch {
            try {
                turn.body()
            } finally {
                noteClientTurnEnded(turn)
                turn.journal.close(terminalObserved = turn.fanout.anyTerminalWritten)
            }
        }
        job.invokeOnCompletion { live.decrementAndGet() }
        try {
            job.join()
        } catch (cancelled: CancellationException) {
            detach(turn, job)
            throw cancelled
        }
    }

    /**
     * Relays the turns the App Server starts by itself (a background task finishing after the client's
     * turn closed, a wake, a cron run, an inbox arrival) the way a client turn is relayed: each is opened,
     * journaled and closed in its conversation's feed through a [ConversationTurnFanout] with no initiator,
     * and fanned out to that conversation's viewers through [delivery].
     *
     * [frames] is every runtime-scoped App Server frame. Each frame is keyed on its own runtime scope. A
     * frame a client turn answers for ([clientOwns]) is that turn's and is not relayed twice; a turn opened
     * here keeps its conversation's frames until the server's `turn_finished` closes it.
     */
    internal fun relayServerStartedTurns(frames: Flow<AppServerReceivedFrame>, delivery: TurnFanoutDelivery): Job =
        scope.launch {
            val open = HashMap<AppServerRuntimeScope, NodeTurn>()
            try {
                frames.collect { received -> relayServerStartedFrame(received, open, delivery) }
            } finally {
                open.values.forEach { it.journal.close(terminalObserved = it.fanout.anyTerminalWritten) }
            }
        }

    private suspend fun relayServerStartedFrame(
        received: AppServerReceivedFrame,
        open: MutableMap<AppServerRuntimeScope, NodeTurn>,
        delivery: TurnFanoutDelivery,
    ) {
        val frame = received.frame
        val runtime = frame.runtime ?: return
        val key = AppServerRuntimeScope(runtime.agentId, runtime.conversationId)
        val turn = open[key]
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> {
                if (frame.subagentId != null) return
                val delta = frame.delta as? JsonObject ?: return
                val started = turn ?: if (clientOwns(key.conversationId, delta.runIdOrNull())) {
                    return
                } else {
                    openServerStartedTurn(key, delivery).also { open[key] = it }
                }
                started.fanout.relayServerStartedDelta(received.raw)
            }
            is AppServerInboundFrame.TurnFinished -> {
                val finished = turn ?: return
                open.remove(key)
                finished.journal.close(terminalObserved = true)
                Telemetry.event(
                    "IrohNode", "turn.server_started_closed",
                    "conversationId" attr key.conversationId,
                    "stopReason" attr frame.stopReason,
                )
            }
            else -> Unit
        }
    }

    private fun openServerStartedTurn(runtime: AppServerRuntimeScope, delivery: TurnFanoutDelivery): NodeTurn {
        val turnId = "turn-${UUID.randomUUID()}"
        val writer = journal.openTurn(AgentId(runtime.agentId), ConversationId(runtime.conversationId), turnId)
        val fanout = ConversationTurnFanout(
            conversationId = runtime.conversationId,
            runtime = runtime,
            delivery = delivery,
            initiatorViewer = null,
            trackInitiatorFrame = writer::append,
            turnId = turnId,
        )
        Telemetry.event("IrohNode", "turn.server_started_opened", "conversationId" attr runtime.conversationId)
        return NodeTurn(runtime.conversationId, clientMessageId = null, fanout = fanout, journal = writer) {}
    }

    /** Stops every live turn (node shutdown only). */
    fun shutdown() {
        scope.cancel(CancellationException("Iroh node turn host shut down"))
    }

    private fun detach(turn: NodeTurn, job: Job) {
        if (job.isCompleted) return
        turn.fanout.detachInitiator()
        // The initiator never saw this turn's tail: record that now, so a wrapper restart before the turn ends still owes it.
        turn.journal.noteInitiatorMissedTail()
        Telemetry.event(
            "IrohNode", "turn.detached",
            "conversationId" attr turn.conversationId,
            "clientMessageId" attr (turn.clientMessageId ?: "<none>"),
            "liveTurns" attr live.get(),
        )
        job.invokeOnCompletion { cause ->
            Telemetry.event(
                "IrohNode", "turn.detached_journaled",
                "conversationId" attr turn.conversationId,
                "clientMessageId" attr (turn.clientMessageId ?: "<none>"),
                "serverTerminal" attr turn.fanout.anyTerminalWritten,
                "cause" attr (cause?.let { it::class.simpleName } ?: ""),
            )
        }
    }

    companion object {
        /** Runs remembered per conversation for [clientOwns]; a turn rarely binds more than a few. */
        private const val REMEMBERED_CLIENT_RUNS = 256

        private val handler = CoroutineExceptionHandler { _, throwable ->
            Telemetry.event(
                "IrohNode", "turn.host_crash",
                "error" attr (throwable.message ?: throwable.toString()),
                "class" attr throwable::class.simpleName,
                level = Telemetry.Level.WARN,
            )
        }

        /** A host whose turns are children of [parent] (the endpoint's scope) but of no connection, recording in [journal]. */
        @Suppress("NoDetachedCoroutineLifecycle") // Node-owned scope tied to parent endpoint lifecycle.
        fun childOf(parent: CoroutineScope, journal: TurnJournal = NoTurnJournal): NodeTurnHost = NodeTurnHost(
            CoroutineScope(
                parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + handler,
            ),
            journal,
        )

        /** Process-scoped fallback for constructions without an endpoint (legacy/tests). */
        @Suppress("NoDetachedCoroutineLifecycle") // Fallback shared scope for endpointless tests.
        internal val SHARED: NodeTurnHost by lazy {
            NodeTurnHost(CoroutineScope(SupervisorJob() + Dispatchers.Default + handler))
        }
    }
}

private fun JsonObject.runIdOrNull(): String? = (this["run_id"] as? JsonPrimitive)?.contentOrNull

/** One turn handed to [NodeTurnHost]: its fanout, its journal writer, and its collector. */
internal class NodeTurn(
    val conversationId: String,
    val clientMessageId: String?,
    val fanout: ConversationTurnFanout,
    val journal: TurnJournalWriter,
    val body: suspend () -> Unit,
)
