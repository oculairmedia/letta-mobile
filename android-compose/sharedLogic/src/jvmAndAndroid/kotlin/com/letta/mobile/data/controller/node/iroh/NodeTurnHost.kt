package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * letta-mobile-qygvv.3: the node-owned home of every live Iroh turn.
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
 * external-tool answering, fanout to the remaining viewers, and parking of the finished turn for
 * the initiator's redial replay.
 */
class NodeTurnHost internal constructor(
    internal val scope: CoroutineScope,
) {
    /** Serialized writes to observer viewers; node-owned so a detached turn still reaches them. */
    internal val observerWrites = ObserverWriteQueue(scope)

    private val live = AtomicInteger(0)

    /** Turns currently running on this host, attached or detached (telemetry/tests). */
    val liveTurnCount: Int get() = live.get()

    /**
     * Runs [turn] on the node scope and waits for it. If the caller (the initiator's connection)
     * is cancelled first, the turn keeps running detached and this rethrows the cancellation.
     */
    internal suspend fun run(turn: NodeTurn) {
        live.incrementAndGet()
        val job = scope.launch { turn.body() }
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
        Telemetry.event(
            "IrohNode", "turn.detached",
            "conversationId" to turn.conversationId,
            "clientMessageId" to (turn.clientMessageId ?: "<none>"),
            "liveTurns" to live.get(),
        )
        job.invokeOnCompletion { cause -> parkForRedial(turn, cause) }
    }

    /**
     * The initiator never saw this turn's tail. Park it so the initiator's redial re-send of the
     * same client message id replays it (see [ParkedTerminalStore]).
     */
    private fun parkForRedial(turn: NodeTurn, cause: Throwable?) {
        val key = turn.clientMessageId ?: return
        val terminal = if (turn.fanout.anyTerminalWritten) null else INTERRUPTED_TERMINAL
        turn.tracker.parkFrames(turn.parkedTerminals, key, terminal)
        Telemetry.event(
            "IrohNode", "turn.detached_parked",
            "conversationId" to turn.conversationId,
            "clientMessageId" to key,
            "frameCount" to turn.tracker.frameCount(),
            "serverTerminal" to (terminal == null),
            "cause" to (cause?.let { it::class.simpleName } ?: ""),
        )
    }

    companion object {
        private val handler = CoroutineExceptionHandler { _, throwable ->
            Telemetry.event(
                "IrohNode", "turn.host_crash",
                "error" to (throwable.message ?: throwable.toString()),
                "class" to throwable::class.simpleName,
                level = Telemetry.Level.WARN,
            )
        }

        /** A host whose turns are children of [parent] (the endpoint's scope) but of no connection. */
        fun childOf(parent: CoroutineScope): NodeTurnHost = NodeTurnHost(
            CoroutineScope(
                parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + handler,
            ),
        )

        /** Process-scoped fallback for constructions without an endpoint (legacy/tests). */
        internal val SHARED: NodeTurnHost by lazy {
            NodeTurnHost(CoroutineScope(SupervisorJob() + Dispatchers.Default + handler))
        }

        internal val INTERRUPTED_TERMINAL: String = buildJsonObject {
            put("message_type", "error_message")
            put("message", "connection interrupted before the turn completed")
            put("status", "cancelled")
        }.toString()
    }
}

/** One turn handed to [NodeTurnHost]: its fanout, its initiator parking record, and its collector. */
internal class NodeTurn(
    val conversationId: String,
    val clientMessageId: String?,
    val fanout: ConversationTurnFanout,
    val tracker: TurnFrameTracker,
    val parkedTerminals: ParkedTerminalStore,
    val body: suspend () -> Unit,
)
