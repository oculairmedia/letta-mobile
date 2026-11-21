package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
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
        val job = scope.launch {
            try {
                turn.body()
            } finally {
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

/** One turn handed to [NodeTurnHost]: its fanout, its journal writer, and its collector. */
internal class NodeTurn(
    val conversationId: String,
    val clientMessageId: String?,
    val fanout: ConversationTurnFanout,
    val journal: TurnJournalWriter,
    val body: suspend () -> Unit,
)
