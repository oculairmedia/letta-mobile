package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.RunId
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * The shutdown drain in progress. Set once by [beginDrain] and never cleared: a draining host
     * is going away. While it drains the host refuses new turn inputs ([isDraining]) and
     * [awaitDrained] waits for the live ones, so closing the App Server connection afterwards
     * cancels no in-flight turn with cause "transport" (the App Server's reaction to its listener
     * connection dropping mid-turn).
     */
    private val draining = AtomicBoolean(false)

    /** Completed by the drain's cut (what [beginDrain] returned): [awaitDrained] then stops waiting at once. */
    private val drainCut = CompletableDeferred<Unit>()

    /** Guards [awaitDrained] against a second caller: the drain's wait runs once. */
    private val drainAwaited = AtomicBoolean(false)

    /** The drain's bound, start and initial turn count, recorded by [beginDrain] for its telemetry. */
    @Volatile
    private var drainBoundMs: Long = 0

    @Volatile
    private var drainStartedAtMs: Long = 0

    @Volatile
    private var drainWaitedTurns: Int = 0

    /**
     * Begins this host's shutdown drain: [isDraining] turns true, so new turn inputs are refused
     * while the already-active turns keep running and relaying, and the drain's [bound] is
     * recorded for [awaitDrained]. Returns the drain's cut — invoking it once makes
     * [awaitDrained] stop waiting at once (a second shutdown signal) — or null when a drain
     * already began.
     */
    internal fun beginDrain(bound: Duration, nowMs: () -> Long = System::currentTimeMillis): (() -> Unit)? {
        if (!draining.compareAndSet(false, true)) return null
        drainBoundMs = bound.inWholeMilliseconds
        drainStartedAtMs = nowMs()
        drainWaitedTurns = live.get()
        Telemetry.event(
            "IrohNode", "shutdown.drain.start",
            "active_turns" attr drainWaitedTurns,
            "bound_ms" attr drainBoundMs,
        )
        return { drainCut.complete(Unit) }
    }

    /** Whether this host is draining for shutdown: no new turn inputs are accepted. */
    internal val isDraining: Boolean get() = draining.get()

    /**
     * Waits for the drain begun by [beginDrain] until every live turn ended, the drain's bound
     * elapsed or the drain's cut was invoked, then emits `shutdown.drain.done` with
     * `waited_turns` (the turns the drain began with), `cut_turns` (the turns still live when it
     * ended) and `elapsed_ms`. A turn that starts in the instant after [beginDrain] read the count
     * can be missed: inputs are refused from the moment the drain begins, so the window is the
     * sub-millisecond gap a turn already in flight crosses; such a turn is cancelled by the
     * imminent close exactly as before this drain existed, and its tail is still journaled for the
     * initiator's redial. Runs at most once per host.
     */
    internal suspend fun awaitDrained(nowMs: () -> Long = System::currentTimeMillis) {
        if (!drainAwaited.compareAndSet(false, true)) return
        val drained = withTimeoutOrNull(drainBoundMs.milliseconds) {
            while (live.get() > 0) {
                // Either the cut arrives or a short poll tick passes, so a finishing turn is
                // noticed within DRAIN_POLL_MS and a cut ends the wait at once.
                if (withTimeoutOrNull(DRAIN_POLL_MS.milliseconds) { drainCut.await() } != null) {
                    return@withTimeoutOrNull false
                }
            }
            true
        }
        Telemetry.event(
            "IrohNode", "shutdown.drain.done",
            "waited_turns" attr drainWaitedTurns,
            "cut_turns" attr live.get(),
            "elapsed_ms" attr (nowMs() - drainStartedAtMs),
            "reason" attr when (drained) {
                true -> "drained"
                false -> "cut"
                null -> "bound"
            },
        )
    }

    /** Frames already claimed (by `idempotency_key`), the most recent ones; guarded by itself, with [serverRuns]. */
    private val claimedFrames = LinkedHashSet<String>()

    /** Runs turns the App Server started by itself relay, the most recent ones; guarded by [claimedFrames]. */
    private val serverRuns = LinkedHashSet<RunId>()

    /**
     * Claims one raw App Server frame for the one turn that relays it. The relay of turns the App Server starts by
     * itself reads every frame and a client turn's engine reads its runtime's, so both can see a frame. Under one lock:
     * a [server] claim records [run] as a server-started run; a client claim of a frame on such a run is refused, so that
     * turn keeps all of its run's frames; otherwise the first claim wins. A frame is therefore relayed exactly once, and
     * the relay never refuses a frame of a server-started run, so no frame is refused by both.
     */
    internal fun claimFrame(frameKey: String, run: RunId?, server: Boolean): Boolean = synchronized(claimedFrames) {
        if (server && run != null) serverRuns.rememberRecent(run, REMEMBERED_SERVER_RUNS)
        when {
            !server && run != null && run in serverRuns -> false
            frameKey in claimedFrames -> false
            else -> true.also { claimedFrames.rememberRecent(frameKey, REMEMBERED_FRAMES) }
        }
    }

    /**
     * [runs] were bound to a client turn's prompt (`update_loop_status`): they were never a server-started turn's, so
     * their later frames go to the client turn. Frames already relayed stay where they went (once).
     */
    internal fun releaseServerRuns(runs: Collection<RunId>) {
        synchronized(claimedFrames) { serverRuns.removeAll(runs.toSet()) }
    }

    /** Client turns running per conversation; an entry exists only while one runs. */
    private val clientTurns = ConcurrentHashMap<ConversationId, List<NodeTurn>>()

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
        clientTurns[conversation]?.any { it.clientMessageId in clientMessageIds } == true

    /** Remembers the turn's runs BEFORE it stops counting as running, so its late frames never look unowned. */
    private fun noteClientTurnEnded(turn: NodeTurn) {
        val conversation = ConversationId(turn.conversationId)
        val relayed = turn.fanout.relayedRunIds.filter { it.isNotBlank() }.map(::RunId)
        if (relayed.isNotEmpty()) {
            synchronized(clientRuns) {
                val runs = clientRuns.getOrPut(conversation) { LinkedHashSet() }
                relayed.forEach { runs.rememberRecent(it, REMEMBERED_CLIENT_RUNS) }
            }
        }
        clientTurns.computeIfPresent(conversation) { _, running -> (running - turn).takeIf { it.isNotEmpty() } }
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
        // Lists are immutable and replaced inside compute, so a reader never sees one being changed.
        clientTurns.compute(ConversationId(turn.conversationId)) { _, running -> running.orEmpty() + turn }
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

        /** How often [awaitDrained] re-reads the live turn count between cut signals. */
        private const val DRAIN_POLL_MS = 100L

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

/** Adds [value] as the most recent entry, forgetting the oldest beyond [cap]. */
private fun <T> LinkedHashSet<T>.rememberRecent(value: T, cap: Int) {
    remove(value)
    add(value)
    while (size > cap) remove(first())
}

/** Frames remembered for [NodeTurnHost.claimFrame]; both sides see a frame within milliseconds of each other. */
private const val REMEMBERED_FRAMES = 4_096

/** Server-started runs remembered, so a late frame of one never moves to a client turn. */
private const val REMEMBERED_SERVER_RUNS = 1_024

/** One turn handed to [NodeTurnHost]: its fanout, its journal writer, and its collector. */
internal class NodeTurn(
    val conversationId: String,
    val clientMessageId: String?,
    val fanout: ConversationTurnFanout,
    val journal: TurnJournalWriter,
    val body: suspend () -> Unit,
)
