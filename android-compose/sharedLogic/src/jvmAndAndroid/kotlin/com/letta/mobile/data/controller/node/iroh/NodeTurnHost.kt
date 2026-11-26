package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.RunId
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
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

    /** Frames already claimed (by `idempotency_key`) and the runs turns the App Server started by itself relay. */
    private val claims = FrameClaims()

    /**
     * Claims one raw App Server frame for the one turn that relays it. The relay of turns the App Server starts by
     * itself reads every frame and a client turn's engine reads its runtime's, so both can see a frame. Under one lock:
     * a [server] claim records [run] as a server-started run; a client claim of a frame on such a run is refused, so that
     * turn keeps all of its run's frames; otherwise the first claim wins. A frame is therefore relayed exactly once, and
     * the relay never refuses a frame of a server-started run, so no frame is refused by both.
     */
    internal fun claimFrame(frameKey: String, run: RunId?, server: Boolean): Boolean = claims.claim(frameKey, run, server)

    /**
     * [runs] were bound to a client turn's prompt (`update_loop_status`): they were never a server-started turn's, so
     * their later frames go to the client turn. Frames already relayed stay where they went (once).
     */
    internal fun releaseServerRuns(runs: Collection<RunId>) = claims.release(runs)

    /** Client turns running per conversation; an entry exists only while one runs. */
    private val clientTurns = ConcurrentHashMap<ConversationId, MutableList<NodeTurn>>()

    /** The runs finished client turns relayed, for the most recently used conversations. */
    private val clientRuns = object : LinkedHashMap<ConversationId, LinkedHashSet<RunId>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConversationId, LinkedHashSet<RunId>>?): Boolean =
            size > REMEMBERED_CLIENT_CONVERSATIONS
    }

    /**
     * Whether a client turn answers for [run] on [conversation]: one is running there, or a finished one
     * relayed that run. Frames no client turn answers for belong to a turn the App Server started itself.
     */
    internal fun clientOwns(conversation: ConversationId, run: RunId?): Boolean =
        clientTurns.containsKey(conversation) ||
            (run != null && synchronized(clientRuns) { clientRuns[conversation]?.contains(run) == true })

    /** Whether a running client turn of [conversation] sent one of [clientMessageIds]. */
    internal fun clientSent(conversation: ConversationId, clientMessageIds: Collection<String>): Boolean =
        clientTurns[conversation]?.toList()?.any { it.clientMessageId in clientMessageIds } == true

    private fun noteClientTurnStarted(turn: NodeTurn) {
        clientTurns.compute(ConversationId(turn.conversationId)) { _, running -> (running ?: mutableListOf()).apply { add(turn) } }
    }

    /** Remembers the turn's runs BEFORE it stops counting as running, so its late frames never look unowned. */
    private fun noteClientTurnEnded(turn: NodeTurn) {
        val conversation = ConversationId(turn.conversationId)
        val relayed = turn.fanout.relayedRunIds.filter { it.isNotBlank() }.map(::RunId)
        if (relayed.isNotEmpty()) {
            synchronized(clientRuns) {
                val runs = clientRuns.getOrPut(conversation) { LinkedHashSet() }
                runs += relayed
                while (runs.size > REMEMBERED_CLIENT_RUNS) runs.remove(runs.first())
            }
        }
        clientTurns.computeIfPresent(conversation) { _, running -> running.apply { remove(turn) }.takeIf { it.isNotEmpty() } }
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
        noteClientTurnStarted(turn)
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
     * Relays the turns the App Server starts by itself through [ServerStartedTurnRelay], on this host's scope, to the
     * viewers [delivery] names and into this host's [journal]. [frames] is every runtime-scoped App Server frame.
     */
    internal fun relayServerStartedTurns(
        frames: Flow<AppServerReceivedFrame>,
        delivery: TurnFanoutDelivery,
        nowMs: () -> Long = System::currentTimeMillis,
    ): Job = scope.launch { ServerStartedTurnRelay(this@NodeTurnHost, delivery, nowMs).relay(frames) }

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
        private const val REMEMBERED_CLIENT_RUNS = 64

        /** Conversations whose finished client runs are remembered; the least recently used is forgotten first. */
        private const val REMEMBERED_CLIENT_CONVERSATIONS = 256
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f

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

/** The bookkeeping behind [NodeTurnHost.claimFrame], all under one lock. */
private class FrameClaims {
    private val frames = LinkedHashSet<String>()
    private val serverRuns = LinkedHashSet<RunId>()

    @Synchronized
    fun claim(frameKey: String, run: RunId?, server: Boolean): Boolean {
        if (server && run != null) remember(serverRuns, run, REMEMBERED_SERVER_RUNS)
        if (!server && run != null && run in serverRuns) return false
        if (frameKey in frames) return false
        remember(frames, frameKey, REMEMBERED_FRAMES)
        return true
    }

    @Synchronized
    fun release(runs: Collection<RunId>) {
        serverRuns.removeAll(runs.toSet())
    }

    private fun <T> remember(set: LinkedHashSet<T>, value: T, cap: Int) {
        set.remove(value)
        set.add(value)
        while (set.size > cap) set.remove(set.first())
    }

    private companion object {
        /** Frames remembered; both sides see a frame within milliseconds of each other. */
        const val REMEMBERED_FRAMES = 4_096

        /** Server-started runs remembered, so a late frame of one never moves to a client turn. */
        const val REMEMBERED_SERVER_RUNS = 1_024
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
