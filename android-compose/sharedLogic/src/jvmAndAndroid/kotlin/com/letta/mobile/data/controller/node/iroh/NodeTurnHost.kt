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

    /** Client turns running per conversation; an entry exists only while one runs. */
    private val clientTurns = ConcurrentHashMap<String, MutableList<NodeTurn>>()

    /** The runs finished client turns relayed, for the most recently used conversations, so their late frames open no turn. */
    private val clientRuns = object : LinkedHashMap<String, LinkedHashSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>?): Boolean =
            size > REMEMBERED_CLIENT_CONVERSATIONS
    }

    /**
     * Whether a client turn answers for [runId] on [conversationId]: one is running there, or a finished one
     * relayed that run. Frames it does not answer for belong to a turn the App Server started itself.
     */
    internal fun clientOwns(conversationId: String, runId: String?): Boolean =
        clientTurnRunning(conversationId) || (runId != null && synchronized(clientRuns) { clientRuns[conversationId]?.contains(runId) == true })

    private fun clientTurnRunning(conversationId: String): Boolean = clientTurns.containsKey(conversationId)

    private fun noteClientTurnStarted(turn: NodeTurn) {
        clientTurns.compute(turn.conversationId) { _, running -> (running ?: mutableListOf()).apply { add(turn) } }
    }

    private fun noteClientTurnEnded(turn: NodeTurn) {
        clientTurns.computeIfPresent(turn.conversationId) { _, running -> running.apply { remove(turn) }.takeIf { it.isNotEmpty() } }
        val relayed = turn.fanout.relayedRunIds
        if (relayed.isEmpty()) return
        synchronized(clientRuns) {
            val runs = clientRuns.getOrPut(turn.conversationId) { LinkedHashSet() }
            runs += relayed
            while (runs.size > REMEMBERED_CLIENT_RUNS) runs.remove(runs.first())
        }
    }

    /** Whether a running client turn of [conversationId] sent one of [clientMessageIds]. */
    private fun clientSent(conversationId: String, clientMessageIds: Collection<String>): Boolean =
        clientTurns[conversationId]?.toList()?.any { it.clientMessageId in clientMessageIds } == true

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

    /** The open server-started turn of each runtime; written by the relay's collector only. */
    private val serverTurns = ConcurrentHashMap<AppServerRuntimeScope, NodeTurn>()

    /** When each open server-started turn last had a frame, on the relay's clock. */
    private val serverTurnSeenAt = ConcurrentHashMap<AppServerRuntimeScope, Long>()

    /** The App Server connection generation each open server-started turn began on (absent: unstamped). */
    private val serverTurnGeneration = ConcurrentHashMap<AppServerRuntimeScope, Long>()

    @Volatile
    private var relayClock: () -> Long = System::currentTimeMillis

    /** The runs each runtime's server-started turns relayed, for the most recently used runtimes. */
    private val serverRuns = object : LinkedHashMap<AppServerRuntimeScope, LinkedHashSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AppServerRuntimeScope, LinkedHashSet<String>>?): Boolean =
            size > REMEMBERED_CLIENT_CONVERSATIONS
    }

    /**
     * Whether a turn the App Server started by itself owns a raw stream delta of [runtime] on [runId]: one is open on
     * that runtime and has not gone quiet, or one relayed that run. A client turn's fanout leaves such a delta to it
     * ([TurnFanoutDelivery.serverTurnOwns]), so each frame reaches the feed and the viewers once, in one turn.
     */
    internal fun serverTurnOwns(runtime: AppServerRuntimeScope, runId: String?): Boolean {
        val key = runtime.runtimeKey()
        return isFresh(key) || (runId != null && synchronized(serverRuns) { serverRuns[key]?.contains(runId) == true })
    }

    private fun isFresh(key: AppServerRuntimeScope): Boolean {
        if (!serverTurns.containsKey(key)) return false
        val seenAt = serverTurnSeenAt[key] ?: return false
        return relayClock() - seenAt < SERVER_TURN_SILENCE_MS
    }

    /**
     * Relays the turns the App Server starts by itself (a background task finishing after the client's
     * turn closed, a wake, a cron run, an inbox arrival) the way a client turn is relayed: each is opened,
     * journaled and closed in its conversation's feed through a [ConversationTurnFanout] with no initiator,
     * and fanned out to that conversation's viewers through [delivery].
     *
     * [frames] is every runtime-scoped App Server frame; each is keyed on its own runtime scope. A turn opens
     * on a stream delta no client turn answers for ([clientOwns]). Once open it owns its runtime's stream
     * deltas until the server's `turn_finished`. It is closed without that frame (`terminal_observed`
     * false) when it went quiet for [SERVER_TURN_SILENCE_MS], when the App Server connection generation
     * changed under it, or when `update_loop_status` binds the active run to a running client turn's
     * `client_message_id`; so a lost `turn_finished` can neither hold a later client turn's frames nor
     * relay them twice.
     */
    internal fun relayServerStartedTurns(
        frames: Flow<AppServerReceivedFrame>,
        delivery: TurnFanoutDelivery,
        nowMs: () -> Long = System::currentTimeMillis,
    ): Job {
        relayClock = nowMs
        return scope.launch {
            try {
                frames.collect { received -> relayServerStartedFrame(received, delivery) }
            } finally {
                serverTurns.keys.toList().forEach { closeServerTurn(it, terminalObserved = false, why = "relay_stopped") }
            }
        }
    }

    private suspend fun relayServerStartedFrame(received: AppServerReceivedFrame, delivery: TurnFanoutDelivery) {
        val frame = received.frame
        val key = frame.runtime?.runtimeKey() ?: return
        closeIfSuperseded(key, received)
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> relayServerStartedDelta(key, frame, received, delivery)
            is AppServerInboundFrame.TurnFinished ->
                if (serverTurns.containsKey(key)) closeServerTurn(key, terminalObserved = true, why = frame.stopReason)
            is AppServerInboundFrame.UpdateLoopStatus -> {
                val clientIds = frame.loopStatus.activeRunIds.flatMap { frame.loopStatus.clientMessageIdsByRunId[it].orEmpty() }
                if (serverTurns.containsKey(key) && clientSent(key.conversationId, clientIds)) {
                    // The active run is a client turn's: it was never this turn's, so its frames go back to that turn.
                    val clientRunsNow = frame.loopStatus.activeRunIds.filter { frame.loopStatus.clientMessageIdsByRunId[it].orEmpty().isNotEmpty() }
                    synchronized(serverRuns) { serverRuns[key]?.removeAll(clientRunsNow.toSet()) }
                    closeServerTurn(key, terminalObserved = false, why = "client_run_bound")
                }
            }
            else -> Unit
        }
    }

    private suspend fun relayServerStartedDelta(
        key: AppServerRuntimeScope,
        frame: AppServerInboundFrame.StreamDelta,
        received: AppServerReceivedFrame,
        delivery: TurnFanoutDelivery,
    ) {
        if (frame.subagentId != null) return
        val delta = frame.delta as? JsonObject ?: return
        val turn = serverTurns[key] ?: run {
            if (clientOwns(key.conversationId, delta.runIdOrNull())) return
            openServerStartedTurn(key, delivery, received.connectionGeneration)
        }
        serverTurnSeenAt[key] = relayClock()
        delta.runIdOrNull()?.let { run ->
            synchronized(serverRuns) {
                val runs = serverRuns.getOrPut(key) { LinkedHashSet() }
                runs += run
                while (runs.size > REMEMBERED_CLIENT_RUNS) runs.remove(runs.first())
            }
        }
        turn.fanout.relayServerStartedDelta(received.raw)
    }

    /** A turn that went quiet, or began on an earlier App Server connection, lost its `turn_finished`. */
    private fun closeIfSuperseded(key: AppServerRuntimeScope, received: AppServerReceivedFrame) {
        if (!serverTurns.containsKey(key)) return
        val began = serverTurnGeneration[key]
        val now = received.connectionGeneration
        val generationChanged = began != null && now != null && began != now
        if (generationChanged || !isFresh(key)) {
            closeServerTurn(key, terminalObserved = false, why = if (generationChanged) "generation_changed" else "silent")
        }
    }

    private fun openServerStartedTurn(runtime: AppServerRuntimeScope, delivery: TurnFanoutDelivery, generation: Long?): NodeTurn {
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
        val turn = NodeTurn(runtime.conversationId, clientMessageId = null, fanout = fanout, journal = writer) {}
        generation?.let { serverTurnGeneration[runtime] = it }
        serverTurnSeenAt[runtime] = relayClock()
        serverTurns[runtime] = turn
        Telemetry.event("IrohNode", "turn.server_started_opened", "conversationId" attr runtime.conversationId, "turnId" attr turnId)
        return turn
    }

    private fun closeServerTurn(key: AppServerRuntimeScope, terminalObserved: Boolean, why: String) {
        val turn = serverTurns.remove(key) ?: return
        serverTurnSeenAt.remove(key)
        serverTurnGeneration.remove(key)
        turn.journal.close(terminalObserved = terminalObserved || turn.fanout.anyTerminalWritten)
        Telemetry.event(
            "IrohNode", "turn.server_started_closed",
            "conversationId" attr key.conversationId,
            "terminalObserved" attr terminalObserved,
            "reason" attr why,
        )
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
        private const val REMEMBERED_CLIENT_RUNS = 64

        /** Conversations whose finished client runs are remembered; the least recently used is forgotten first. */
        private const val REMEMBERED_CLIENT_CONVERSATIONS = 256

        /** A server-started turn with no frame for this long lost its `turn_finished`: the engine's turn idle bound. */
        internal const val SERVER_TURN_SILENCE_MS = 300_000L

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

/** The `{agent, conversation}` key of this scope, without the acting user. */
private fun AppServerRuntimeScope.runtimeKey() = AppServerRuntimeScope(agentId, conversationId)

private fun JsonObject.runIdOrNull(): String? = (this["run_id"] as? JsonPrimitive)?.contentOrNull

/** One turn handed to [NodeTurnHost]: its fanout, its journal writer, and its collector. */
internal class NodeTurn(
    val conversationId: String,
    val clientMessageId: String?,
    val fanout: ConversationTurnFanout,
    val journal: TurnJournalWriter,
    val body: suspend () -> Unit,
)
