package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.feed.FeedJournalStore
import com.letta.mobile.data.controller.node.iroh.feed.JournalKey
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A turn the App Server starts by itself (here: a background task's notification arriving after the client's
 * turn closed) is opened, journaled and closed in the conversation's feed and reaches its viewers, like a turn a
 * client started. The frames are shaped like the ones the App Server sent for conversation local-conv-95806538.
 */
class ServerStartedTurnFeedTest {
    private class RecordingViewer(override val connectionId: String) : ViewerHandle {
        val frames = mutableListOf<String>()
        override suspend fun writeFrame(frame: String): Boolean {
            frames += frame
            return true
        }
    }

    private class Node(val host: NodeTurnHost, val journal: FeedJournalStore, val frames: MutableSharedFlow<AppServerReceivedFrame>) {
        var nowMs = 0L
    }

    private fun TestScope.node(viewers: () -> Set<ViewerHandle>): Node {
        val journal = FeedJournalStore()
        val hostScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val host = NodeTurnHost(hostScope, journal)
        val frames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val node = Node(host, journal, frames)
        // The relay's clock is virtual time plus a manual offset, so tests can age a turn with or without running its timer.
        val clock = { testScheduler.currentTime + node.nowMs }
        host.relayServerStartedTurns(frames, TurnFanoutDelivery(viewersFor = { viewers() }), nowMs = clock)
        runCurrent()
        return node
    }

    /** A client turn of the conversation, with [CLIENT_TURN] as its prompt's client message id, relaying what its engine hands it. */
    private class ClientTurn(val job: Job, val engine: kotlinx.coroutines.channels.Channel<String>, val finish: CompletableDeferred<Unit>)

    private fun TestScope.clientTurn(node: Node, viewers: () -> Set<ViewerHandle> = { emptySet() }): ClientTurn {
        val writer = node.journal.openTurn(AGENT, CONVERSATION, CLIENT_TURN)
        val fanout = ConversationTurnFanout(
            conversationId = CONVERSATION.value,
            runtime = RUNTIME_SCOPE,
            delivery = TurnFanoutDelivery(
                viewersFor = { viewers() },
                claimFrame = { key, run -> node.host.claimFrame(key, run, server = false) },
            ),
            initiatorViewer = null,
            trackInitiatorFrame = writer::append,
            turnId = CLIENT_TURN,
        )
        val engine = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            node.host.run(
                NodeTurn(CONVERSATION.value, CLIENT_TURN, fanout, writer) {
                    launch { for (raw in engine) fanout.onDraft(RuntimeEventPayload.RemoteStreamFrame(frameId = raw, body = raw)) }
                    finish.await()
                    engine.close()
                },
            )
        }
        return ClientTurn(job, engine, finish)
    }

    /** What the App Server sends once, delivered to the node's standing relay and to the client turn's engine. */
    private suspend fun Node.sendToBoth(client: ClientTurn, raw: String) {
        frames.emit(decode(raw))
        client.engine.send(raw)
    }

    private suspend fun TestScope.finishedClientTurn(node: Node, relay: List<String>) {
        val client = clientTurn(node)
        relay.forEach { client.engine.send(it) }
        runCurrent()
        client.finish.complete(Unit)
        client.job.join()
    }

    @Test
    fun aTurnTheServerStartsAfterTheClientTurnClosedReachesTheFeedAndTheViewers() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        finishedClientTurn(node, listOf(assistant("Still running: task_42.", run = "local-run-1206")))
        viewer.frames.clear()

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        val serverTurn = entries(node.journal).filter { it.turn != CLIENT_TURN }
        assertEquals("turn_open", serverTurn.firstOrNull()?.kind, "the server-started turn is opened in the feed: $serverTurn")
        assertEquals(
            listOf("user_message", "assistant_message", "usage_statistics", "stop_reason"),
            serverTurn.filter { it.kind == "stream_delta" }.map { it.payload?.text("message_type") },
        )
        assertEquals("turn_closed", serverTurn.last().kind)
        assertEquals("true", serverTurn.last().payload?.get("terminal_observed").toString())
        val delivered = viewer.frames.map { it.delta().text("message_type") }
        assertEquals(listOf("user_message", "assistant_message", "usage_statistics", "stop_reason"), delivered)
        assertEquals(REPLY, viewer.frames[1].delta().text("content"))
    }

    @Test
    fun childAgentFramesOnAServerStartedTurnAreStillRefused() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }

        listOf(childUsage(), childStop(), childSubagentDelta(), turnFinished()).forEach { node.frames.emit(it) }
        runCurrent()

        assertTrue(entries(node.journal).isEmpty(), "nothing of a child agent is journaled: ${entries(node.journal)}")
        assertTrue(viewer.frames.isEmpty(), "nothing of a child agent reaches a viewer: ${viewer.frames}")
    }

    @Test
    fun framesOfARunningClientTurnAreRelayedOnceByThatTurn() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        val client = clientTurn(node) { setOf(viewer) }
        runCurrent()

        clientRun().forEach { node.sendToBoth(client, it) }
        runCurrent()
        client.finish.complete(Unit)
        client.job.join()

        assertEquals(setOf(CLIENT_TURN), entries(node.journal).map { it.turn }.toSet(), "one turn, the client's")
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.kinds(), "one copy of each frame")
    }

    @Test
    fun lateFramesOfAFinishedClientRunOpenNoServerTurn() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        finishedClientTurn(node, listOf(assistant("Done.", run = CLIENT_RUN)))
        viewer.frames.clear()

        node.frames.emit(decode(assistant("Done. And more.", run = CLIENT_RUN)))
        runCurrent()

        assertTrue(entries(node.journal).none { it.turn != CLIENT_TURN }, "the client run's late frame is not a new turn")
        assertTrue(viewer.frames.isEmpty())
    }

    /**
     * A client sends while a server-started turn runs. The App Server queues the input behind it, so the server
     * turn's remaining frames belong to the server-started turn, which keeps them; the client's own run starts after
     * the server's `turn_finished` and belongs to the client turn. Each frame reaches the feed and the viewer once.
     */
    @Test
    fun aClientTurnStartingDuringAServerStartedTurnLeavesItsFramesToItAndKeepsItsOwn() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        val server = serverStartedTurn()
        node.frames.emit(server[0])
        node.frames.emit(server[1])
        runCurrent()

        val client = clientTurn(node) { setOf(viewer) }
        runCurrent()
        // The node's engine hands its turn every frame of the runtime it does not drop; the relay sees them all too.
        server.drop(2).dropLast(1).forEach { node.frames.emit(it); client.engine.send(it.raw.toString()) }
        node.frames.emit(server.last())
        runCurrent()
        clientRun().forEach { node.sendToBoth(client, it) }
        runCurrent()
        client.finish.complete(Unit)
        client.job.join()

        val byTurn = entries(node.journal).filter { it.kind == "stream_delta" }.groupBy({ it.turn }) { it.payload?.text("message_type") }
        val serverTurn = byTurn.keys.single { it != CLIENT_TURN }
        assertEquals(listOf("user_message", "assistant_message", "usage_statistics", "stop_reason"), byTurn[serverTurn])
        assertEquals(listOf("assistant_message", "stop_reason"), byTurn[CLIENT_TURN])
        assertEquals(
            listOf("user_message", "assistant_message", "usage_statistics", "stop_reason", "assistant_message", "stop_reason"),
            viewer.frames.map { it.delta().text("message_type") },
            "every frame once, in server order",
        )
    }

    /** A server-started turn whose `turn_finished` was lost closes when it goes quiet, and a later client turn keeps its frames. */
    @Test
    fun aServerStartedTurnThatNeverFinishesClosesAfterSilenceAndStealsNothing() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        // The App Server went away mid-turn: neither its stop_reason nor its turn_finished arrives.
        serverStartedTurn().dropLast(2).forEach { node.frames.emit(it) }
        runCurrent()
        node.nowMs += SERVER_TURN_SILENCE_MS
        viewer.frames.clear()

        val client = clientTurn(node) { setOf(viewer) }
        runCurrent()
        clientRun().forEach { node.sendToBoth(client, it) }
        runCurrent()
        client.finish.complete(Unit)
        client.job.join()

        val journal = entries(node.journal)
        val serverTurn = journal.first { it.turn != CLIENT_TURN }.turn
        assertEquals("turn_closed", journal.last { it.turn == serverTurn }.kind)
        assertEquals("false", journal.last { it.turn == serverTurn }.payload?.get("terminal_observed").toString(), "no terminal was seen")
        assertEquals(listOf("assistant_message", "stop_reason"), journal.deltaKinds(CLIENT_TURN))
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.frames.map { it.delta().text("message_type") })
    }

    /** A turn whose `turn_finished` was lost and whose runtime then sends nothing still closes on the bound, on a timer. */
    @Test
    fun aSilentServerStartedTurnClosesOnTheBoundWithoutAnotherFrame() = runTest {
        val node = node { emptySet() }
        serverStartedTurn().dropLast(2).forEach { node.frames.emit(it) }
        runCurrent()

        advanceTimeBy(SERVER_TURN_SILENCE_MS + SILENCE_CHECK_SLACK_MS)
        runCurrent()

        val closed = entries(node.journal).lastOrNull()
        assertEquals("turn_closed", closed?.kind, "the turn closed with no further frame: ${entries(node.journal).map { it.kind }}")
        assertEquals("false", closed?.payload?.get("terminal_observed").toString())
    }

    /**
     * A server-started turn runs one client-side tool for longer than the bound. The App Server says so
     * (`update_loop_status` EXECUTING_CLIENT_SIDE_TOOL) and then sends the tool's result on the same run: the run stays
     * one turn, closed by the server's own `turn_finished`, with every frame once.
     */
    @Test
    fun aLongToolDoesNotSplitAServerStartedRun() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        val call = frame(
            delta(
                mapOf(
                    "message_type" to "tool_call_message", "run_id" to RUN, "type" to "message",
                    "id" to "tool-call-toolu_long",
                ),
            ),
        )
        listOf(serverStartedTurn()[0], decode(call), decode(serverLoopStatus(RUN, "EXECUTING_CLIENT_SIDE_TOOL"))).forEach {
            node.frames.emit(it)
        }
        runCurrent()

        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()
        val result = frame(
            delta(mapOf("message_type" to "tool_return_message", "run_id" to RUN, "type" to "message", "tool_call_id" to "toolu_long")),
        )
        listOf(decode(result), decode(stop(RUN)), turnFinished()).forEach { node.frames.emit(it) }
        runCurrent()

        val journal = entries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "one turn: ${journal.map { it.kind }}")
        assertEquals(1, journal.count { it.kind == "turn_closed" })
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
        assertEquals(
            listOf("user_message", "tool_call_message", "tool_return_message", "stop_reason"),
            viewer.kinds(),
            "every frame once",
        )
    }

    /** A turn running a client-side tool: its prompt, the tool call, and the server's EXECUTING_CLIENT_SIDE_TOOL. */
    private fun busyTurn(): List<AppServerReceivedFrame> = listOf(
        serverStartedTurn()[0],
        decode(frame(delta(mapOf("message_type" to "tool_call_message", "run_id" to RUN, "id" to "tool-call-toolu_lost")))),
        decode(serverLoopStatus(RUN, "EXECUTING_CLIENT_SIDE_TOOL")),
    )

    /**
     * A busy turn loses its `turn_finished`. Silence alone never closes it (the tool may legitimately run for hours),
     * but any later loop status that is not a long wait, here the idle one the server sends right before
     * `turn_finished`, ends the tool exemption: the turn closes within the silence bound after it, as not truly ended.
     * This pins behaviour the relay already had before the idle status was read as evidence (any non-waiting status
     * cleared the exemption); it is not evidence of the later change.
     */
    @Test
    fun aBusyTurnWhoseFinishIsLostClosesWithinTheBoundAfterANonWaitingLoopStatus() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        runCurrent()

        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()
        assertStillOpen(node)

        node.frames.emit(decode(toolReturn()))
        node.frames.emit(decode(idleLoopStatus()))
        advanceTimeBy(SERVER_TURN_SILENCE_MS + SILENCE_CHECK_SLACK_MS)
        runCurrent()

        val closed = entries(node.journal).last()
        assertEquals("turn_closed", closed.kind, "${entries(node.journal).map { it.kind }}")
        assertEquals("false", closed.payload?.get("terminal_observed").toString(), "no terminal was seen")
    }

    /**
     * A busy turn's tool returns, its `turn_finished` is lost, the server says the runtime went idle, and the server's
     * next turn starts at once: that turn's first delta (a new run) closes the lost one, as not truly ended, and opens
     * its own turn instead of being folded into the lost one.
     */
    @Test
    fun aBusyTurnWhoseFinishIsLostClosesWhenTheNextServerTurnStartsAfterIdle() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()
        assertStillOpen(node)

        node.frames.emit(decode(toolReturn()))
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(decode(assistant("Next notification handled.", run = NEXT_RUN)))
        runCurrent()

        val journal = entries(node.journal)
        val turns = journal.map { it.turn }.distinct()
        assertEquals(2, turns.size, "${journal.map { it.turn to it.kind }}")
        val first = journal.filter { it.turn == turns[0] }
        assertEquals("turn_closed", first.last().kind)
        assertEquals("false", first.last().payload?.get("terminal_observed").toString())
        assertEquals(listOf("turn_open", "stream_delta"), journal.filter { it.turn == turns[1] }.map { it.kind })
    }

    /**
     * The App Server sends its idle loop status just BEFORE `turn_finished` when a turn ends (letta-code
     * finishListenerTurn: emitRuntimeStateUpdates, then emitDurableTurnFinished). A turn that ends normally still
     * closes on its `turn_finished`, as truly ended, with no second turn.
     */
    @Test
    fun anIdleLoopStatusRightBeforeTurnFinishedStillEndsTheTurnAsTrulyEnded() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(serverLoopStatus(RUN, "PROCESSING_API_RESPONSE")))
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(turnFinished())
        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()

        val journal = entries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_closed" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /**
     * An aborted turn: the server says idle as soon as the abort is requested (letta-code requestCancellation) and
     * sends the turn's remaining frames and `turn_finished` once the cancelled tool settled. Those stay one turn, closed
     * as truly ended, when they arrive within the silence bound after the idle status, however long the tool ran.
     */
    @Test
    fun anAbortedBusyTurnSettlingAfterItsIdleStatusStaysOneTurn() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        node.frames.emit(decode(idleLoopStatus()))
        runCurrent()

        advanceTimeBy(SERVER_TURN_SILENCE_MS / 2)
        listOf(decode(toolReturn()), decode(stop(RUN, reason = "cancelled")), turnFinished()).forEach { node.frames.emit(it) }
        runCurrent()

        val journal = entries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /**
     * The idle status the server can send between approval-continuation rounds (after a `requires_approval` stop) is
     * not the end of the turn: the same rule a client turn's boundary gate applies. The next round's run then stays in
     * this turn instead of closing it.
     */
    @Test
    fun anIdleBetweenApprovalRoundsDoesNotEndTheTurn() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(stop(RUN, reason = "requires_approval")))
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(decode(assistant("Approved; continuing.", run = NEXT_RUN)))
        node.frames.emit(decode(stop(NEXT_RUN)))
        node.frames.emit(turnFinished(NEXT_RUN))
        runCurrent()

        val journal = entries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /**
     * While a server-started turn runs, the server's loop status may name no run (before the run's first chunk, or
     * after an approval-only continuation cleared it) but is then not idle. A delta of another run (a client's run the
     * server started after this turn) does not close the turn as "server idle".
     */
    @Test
    fun aRunlessButBusyLoopStatusDoesNotArmTheIdleClose() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(runlessLoopStatus("PROCESSING_API_RESPONSE")))
        node.frames.emit(decode(assistant("Still the same turn.", run = NEXT_RUN)))
        runCurrent()

        val kinds = entries(node.journal).map { it.kind }
        assertEquals(1, kinds.count { it == "turn_open" }, "$kinds")
        assertTrue("turn_closed" !in kinds, "$kinds")
    }

    /**
     * The server says WAITING_ON_INPUT while it still names an active run: a turn is still running, so the runtime is
     * not idle. A delta of another run then does not close the turn as "server idle".
     */
    @Test
    fun aWaitingOnInputStatusThatStillNamesAnActiveRunDoesNotArmTheIdleClose() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(serverLoopStatus(RUN, "WAITING_ON_INPUT")))
        node.frames.emit(decode(assistant("Still the same turn.", run = NEXT_RUN)))
        runCurrent()

        val kinds = entries(node.journal).map { it.kind }
        assertEquals(1, kinds.count { it == "turn_open" }, "$kinds")
        assertTrue("turn_closed" !in kinds, "$kinds")
    }

    /**
     * While the parent turn is idle (aborted and settling), a child agent's delta carries a run of its own. It is not
     * the parent's next turn, so it neither closes the turn as "server idle" nor is relayed; the parent's own
     * `turn_finished` then still closes the one turn as truly ended.
     */
    @Test
    fun aChildAgentDeltaOfAnotherRunDoesNotCloseAnIdleParentTurn() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(childSubagentDelta())
        runCurrent()

        assertStillOpen(node)

        node.frames.emit(decode(toolReturn()))
        node.frames.emit(turnFinished())
        runCurrent()

        val journal = entries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals(1, journal.count { it.kind == "turn_closed" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /** A pending approval is a wait too: silence alone keeps the turn open. */
    @Test
    fun anApprovalWaitKeepsABusyTurnOpenThroughSilence() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(serverLoopStatus(RUN, "WAITING_ON_APPROVAL")))
        runCurrent()

        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()

        assertTrue(entries(node.journal).none { it.kind == "turn_closed" }, "${entries(node.journal).map { it.kind }}")
    }

    /**
     * A busy turn whose App Server connection was replaced: the new generation shows on a frame of another runtime
     * (this runtime sends nothing again), and that alone closes the turn, as not truly ended. Silence before it does not.
     */
    @Test
    fun aBusyTurnWhoseFinishIsLostClosesWhenAnyFrameShowsANewConnectionGeneration() = runTest {
        val node = node { emptySet() }
        busyTurn().forEach { node.frames.emit(it.copy(connectionGeneration = 1L)) }
        runCurrent()

        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()
        assertStillOpen(node)

        node.frames.emit(decode(otherRuntimeLoopStatus()).copy(connectionGeneration = 2L))
        runCurrent()

        val closed = entries(node.journal).last()
        assertEquals("turn_closed", closed.kind, "${entries(node.journal).map { it.kind }}")
        assertEquals("false", closed.payload?.get("terminal_observed").toString())
    }

    /** A loop status this relay does not know is "not waiting" (the silence bound applies) and is logged once by name. */
    @Test
    fun anUnknownLoopStatusIsNotWaitingAndIsLoggedOnceByName() = runTest {
        val recorder = com.letta.mobile.util.TelemetryRecorder()
        com.letta.mobile.util.Telemetry.installRecorder(recorder).use {
            val node = node { emptySet() }
            busyTurn().forEach { node.frames.emit(it) }
            node.frames.emit(decode(serverLoopStatus(RUN, FUTURE_STATUS)))
            node.frames.emit(decode(serverLoopStatus(RUN, FUTURE_STATUS)))
            runCurrent()

            advanceTimeBy(SERVER_TURN_SILENCE_MS + SILENCE_CHECK_SLACK_MS)
            runCurrent()

            val closed = entries(node.journal).last()
            assertEquals("turn_closed", closed.kind, "an unknown status does not exempt the turn: ${entries(node.journal).map { it.kind }}")
            assertEquals("false", closed.payload?.get("terminal_observed").toString())
            val logged = recorder.snapshot().filter {
                it.name == "turn.server_started_unknown_loop_status" && it.attrs["status"]?.toString() == FUTURE_STATUS
            }
            assertEquals(1, logged.size, "logged once by name: ${recorder.snapshot().map { it.name }}")
        }
    }

    /** A later frame on the open turn's own run proves it alive even after a quiet stretch with no loop status. */
    @Test
    fun aFrameOnTheOpenRunAfterSilenceKeepsTheTurn() = runTest {
        val node = node { emptySet() }
        serverStartedTurn().take(2).forEach { node.frames.emit(it) }
        runCurrent()

        node.nowMs += 2 * SERVER_TURN_SILENCE_MS
        listOf(decode(stop(RUN)), turnFinished()).forEach { node.frames.emit(it) }
        runCurrent()

        val journal = entries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /** The same, when the client's run is bound by `update_loop_status` before the silence bound passes. */
    @Test
    fun aClientRunBoundWhileAServerStartedTurnLostItsFinishGoesToTheClientTurn() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        serverStartedTurn().dropLast(1).forEach { node.frames.emit(it) }
        runCurrent()
        viewer.frames.clear()

        val client = clientTurn(node) { setOf(viewer) }
        runCurrent()
        node.frames.emit(decode(loopStatus(CLIENT_RUN, CLIENT_TURN)))
        runCurrent()
        clientRun().forEach { node.sendToBoth(client, it) }
        runCurrent()
        client.finish.complete(Unit)
        client.job.join()

        assertEquals(listOf("assistant_message", "stop_reason"), entries(node.journal).deltaKinds(CLIENT_TURN))
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.frames.map { it.delta().text("message_type") })
    }

    /** A turn the server starts right after a client turn (inside its post-turn queue window) is relayed once. */
    @Test
    fun aServerStartedTurnRightAfterAClientTurnIsRelayedOnce() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        finishedClientTurn(node, clientRun())
        viewer.frames.clear()

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        assertEquals(SERVER_KINDS, viewer.kinds())
        assertEquals(1, entries(node.journal).count { it.kind == "turn_open" && it.turn != CLIENT_TURN })
    }

    /**
     * The real client relay: relayTurn with its ServerFrameTap, whose post-turn window (ServerFrameTap.relayQueueAfterTurn)
     * forwards only `update_queue` to the initiator and stops at the next turn's first stream delta. A turn the server starts
     * inside that window reaches the phone once, through the node's relay, and the tap forwards none of its deltas.
     */
    @Test
    fun aServerStartedTurnInsideTheClientsPostTurnQueueWindowIsRelayedOnce() = runTest {
        val phoneViewer = RecordingViewer("observer")
        val node = node { setOf(phoneViewer) }
        val phone = FakePhoneLink(RUNTIME_SCOPE, CLIENT_TURN, requestId = "req-1", writeScope = backgroundScope)
        phone.protocol.postTurnScope = backgroundScope
        val clientFrames = clientRun()
        val writer = node.journal.openTurn(AGENT, CONVERSATION, CLIENT_TURN)
        val controller = controllerRunning(node.frames) {
            kotlinx.coroutines.flow.flow {
                clientFrames.forEach { emit(clientDraft(RuntimeEventPayload.RemoteStreamFrame(frameId = key(it), body = it))) }
                emit(clientDraft(RuntimeEventPayload.RunLifecycleChanged(status = com.letta.mobile.runtime.RuntimeRunStatus.Completed)))
            }
        }
        val turn = launch {
            node.host.run(
                NodeTurn(CONVERSATION.value, CLIENT_TURN, phone.fanout, writer) {
                    phone.relay(controller, turnCommandFor(RUNTIME_SCOPE, CLIENT_TURN))
                },
            )
        }
        runCurrent()
        clientFrames.forEach { node.frames.emit(decode(it)) }
        node.frames.emit(turnFinished(CLIENT_RUN))
        runCurrent()
        turn.join()

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()
        advanceTimeBy(POST_TURN_WINDOW_SLACK_MS)

        // The client's own prompt echo (otid = its client message id) is its turn's, not a relayed server frame.
        val tapDeltas = phone.stream
            .filter { it.type == "stream_delta" && it.json["delta"]?.jsonObject?.text("otid") != CLIENT_TURN }
            .map { it.kind }
        assertEquals(listOf("assistant_message", "stop_reason"), tapDeltas, "the client relay carries only its own run")
        assertEquals(SERVER_KINDS, phoneViewer.kinds())
        assertEquals(1, entries(node.journal).count { it.kind == "turn_open" && it.turn != CLIENT_TURN })
    }

    /** A real server-started turn the App Server ran on a `<task-notification>` (feed journal of local-conv-95806538, text redacted). */
    @Test
    fun aCapturedServerStartedTurnReachesTheFeedInFull() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        val captured = resourceLines("/appserver/server-started-turn-deltas.jsonl")

        captured.forEach { node.frames.emit(decode(frame(it))) }
        node.frames.emit(turnFinished())
        runCurrent()

        val journaled = entries(node.journal).filter { it.kind == "stream_delta" }.map { it.payload?.text("message_type") }
        val expected = captured.map { Json.parseToJsonElement(it).jsonObject.text("message_type") }
        assertEquals(expected, journaled)
        assertEquals("turn_closed", entries(node.journal).last().kind)
        assertTrue(viewer.frames.isNotEmpty())
    }

    @Test
    fun aViewerThatConnectsLaterReplaysTheServerStartedTurnFromTheFeed() = runTest {
        val node = node { emptySet() }

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        // feed.subscribe without a cursor serves exactly this page (FeedRoutes over FeedJournalStore.page).
        val replay = entries(node.journal)
        assertEquals(FULL_TURN, replay.map { it.kind })
        assertEquals(REPLY, replay[2].payload?.text("content"))
    }

    /** A delta whose run_id is blank is a frame with no run, never a crash of the relay every conversation shares. */
    @Test
    fun aBlankRunIdIsNoRunAndTheRelayKeepsGoing() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }

        node.frames.emit(decode(frame(delta(mapOf("message_type" to "assistant_message", "run_id" to "", "content" to "blank")))))
        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        val kinds = viewer.frames.map { it.delta().text("message_type") }
        assertEquals(listOf("assistant_message", "user_message", "assistant_message", "usage_statistics", "stop_reason"), kinds)
        assertEquals("turn_closed", entries(node.journal).last().kind)
    }

    /**
     * The claim both sides make is one decision: whichever side claims a frame first, the frame is relayed exactly
     * once, and a server-started run's frame is never refused by both (it would then reach nobody).
     */
    @Test
    fun eachFrameIsClaimedByExactlyOneSideInEitherOrder() = runTest {
        val node = node { emptySet() }
        val run = com.letta.mobile.data.model.RunId(RUN)
        val clientRun = com.letta.mobile.data.model.RunId(CLIENT_RUN)

        val serverFirst = listOf(node.host.claimFrame("k1", run, server = true), node.host.claimFrame("k1", run, server = false))
        val clientFirst = listOf(node.host.claimFrame("k2", run, server = false), node.host.claimFrame("k2", run, server = true))
        val laterClient = node.host.claimFrame("k3", run, server = false)
        val clientOnly = listOf(node.host.claimFrame("k4", clientRun, server = false), node.host.claimFrame("k4", clientRun, server = true))

        assertEquals(listOf(true, false), serverFirst)
        assertEquals(listOf(false, true), clientFirst, "a server-started run's frame stays with that turn")
        assertEquals(false, laterClient, "every later frame of a server-started run stays with it")
        assertEquals(listOf(true, false), clientOnly, "a client run's frame claimed by the client is not relayed again")
    }

    /**
     * Many concurrent claims of the same frames from both sides: each frame is won exactly once. This is the
     * "server turn closes while a client delta asks" race reduced to its one shared decision.
     */
    @Test
    fun concurrentClaimsNeverLoseOrDuplicateAFrame() {
        val host = NodeTurnHost(CoroutineScope(SupervisorJob()), FeedJournalStore())
        val run = com.letta.mobile.data.model.RunId(RUN)
        val keys = (1..500).map { "frame-$it" }
        val wins = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val tasks = keys.flatMap { key ->
            listOf(true, false).map { server ->
                java.util.concurrent.Callable {
                    if (host.claimFrame(key, run, server)) {
                        wins.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                    }
                }
            }
        }
        pool.invokeAll(tasks.shuffled())
        pool.shutdown()
        assertEquals(keys.toSet(), wins.keys, "no frame reached nobody")
        assertTrue(wins.values.all { it.get() == 1 }, "no frame was relayed twice")
    }

    /** The endpoint starts the relay itself: a node whose relay is never started is the bug this guards. */
    @Test
    fun theEndpointStartsTheRelayForItsController() = runTest {
        val registry = ConnectionRegistry()
        val viewer = RecordingViewer("phone")
        registry.register(CONVERSATION.value, viewer)
        val journal = FeedJournalStore()
        val endpoint = IrohNodeEndpoint(
            scope = backgroundScope,
            authPolicy = com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy.InsecureAnonymousForTestOnly,
            connectionRegistry = registry,
            turnJournal = journal,
        )
        val frames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        endpoint.startTurnHost(controllerRunning(frames) { kotlinx.coroutines.flow.emptyFlow() })
        runCurrent()

        serverStartedTurn().forEach { frames.emit(it) }
        runCurrent()

        assertEquals(FULL_TURN, entries(journal).map { it.kind })
        assertEquals(4, viewer.frames.size)
    }

    private fun assertStillOpen(node: Node) {
        val kinds = entries(node.journal).map { it.kind }
        assertTrue("turn_closed" !in kinds, "silence alone keeps a busy turn: $kinds")
    }

    private fun entries(journal: FeedJournalStore): List<FeedDelta> =
        journal.page(JournalKey.of(AGENT, CONVERSATION), from = null, limit = 200).entries

    private fun RecordingViewer.kinds(): List<String?> = frames.map { it.delta().text("message_type") }

    private fun List<FeedDelta>.deltaKinds(turn: String): List<String?> =
        filter { it.turn == turn && it.kind == "stream_delta" }.map { it.payload?.text("message_type") }

    private fun resourceLines(path: String): List<String> =
        requireNotNull(javaClass.getResource(path)).readText().lines().filter { it.isNotBlank() }

    private fun String.delta(): JsonObject = AppServerProtocol.json.parseToJsonElement(this).jsonObject.getValue("delta").jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val AGENT = AgentId("agent-c356b54a-8b37-4d53-b9d0-b43164749b6f")
        val CONVERSATION = ConversationId("local-conv-95806538")
        const val CHILD = "agent-local-2cf9d01d-2328-4145-9779-7127351c2961"
        const val CLIENT_TURN = "e39d7e74-e18c-406f-8045-c15ec1ef31ff"
        const val RUN = "local-run-1207"
        const val CLIENT_RUN = "local-run-1208"
        const val NEXT_RUN = "local-run-1209"
        val RUNTIME_SCOPE = com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(AGENT.value, CONVERSATION.value)
        const val REPLY = "task_42 finished; dispatching the review."
        private val RUNTIME_JSON = AppServerProtocol.json.encodeToJsonElement(
            com.letta.mobile.data.transport.appserver.AppServerRuntimeScope.serializer(),
            com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(AGENT.value, CONVERSATION.value),
        )
        val SERVER_KINDS = listOf("user_message", "assistant_message", "usage_statistics", "stop_reason")
        val FULL_TURN = listOf("turn_open", "stream_delta", "stream_delta", "stream_delta", "stream_delta", "turn_closed")
        private var seq = 4000L

        /** One App Server frame of [type] for the conversation, in the envelope the App Server sends. */
        fun envelope(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
            seq += 1
            return kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive(type))
                put("runtime", RUNTIME_JSON)
                put("event_seq", JsonPrimitive(seq))
                put("emitted_at", JsonPrimitive("2026-11-26T01:23:17.050Z"))
                put("idempotency_key", JsonPrimitive("$type:$seq:k"))
                fields()
            }.toString()
        }

        fun frame(body: String, subagent: String? = null): String = envelope("stream_delta") {
            put("delta", Json.parseToJsonElement(body))
            subagent?.let { put("subagent_id", JsonPrimitive(it)) }
        }

        fun delta(fields: Map<String, String>): String =
            kotlinx.serialization.json.JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()

        fun assistant(text: String, run: String = RUN) = frame(
            delta(
                mapOf(
                    "id" to "ui-msg-9310564", "date" to "2026-11-26T01:23:24.617Z", "agent_id" to AGENT.value,
                    "conversation_id" to CONVERSATION.value, "message_type" to "assistant_message", "run_id" to run,
                    "type" to "message", "content" to text,
                ),
            ),
        )

        fun stop(run: String, reason: String = "end_turn", session: String? = null) = frame(
            delta(
                mapOf("message_type" to "stop_reason", "stop_reason" to reason, "run_id" to run, "type" to "message") +
                    listOfNotNull(session?.let { "session_id" to it }),
            ),
        )

        /** The client's own run: its reply and its end. */
        fun clientRun(): List<String> = listOf(assistant("On it.", run = CLIENT_RUN), stop(CLIENT_RUN))

        fun key(raw: String): String = (Json.parseToJsonElement(raw).jsonObject["idempotency_key"] as JsonPrimitive).content

        fun clientDraft(payload: RuntimeEventPayload) = com.letta.mobile.runtime.RuntimeEventDraft(
            backendId = com.letta.mobile.runtime.BackendId("iroh-node-server"),
            runtimeId = com.letta.mobile.runtime.RuntimeId("iroh-node:${AGENT.value}:${CONVERSATION.value}"),
            agentId = AGENT,
            conversationId = com.letta.mobile.runtime.ConversationId(CONVERSATION.value),
            runId = com.letta.mobile.runtime.RunId(CLIENT_RUN),
            source = com.letta.mobile.runtime.RuntimeEventSource.LocalRuntime,
            payload = payload,
        )

        fun loopStatus(run: String, clientMessageId: String): String = envelope("update_loop_status") {
            val status = """{"status":"PROCESSING_API_RESPONSE","active_run_ids":["$run"],""" +
                """"client_message_ids_by_run_id":{"$run":["$clientMessageId"]}}"""
            put("loop_status", Json.parseToJsonElement(status))
        }

        /** A loop status [status] for [run] with no client prompt bound to it (a turn the server started itself). */
        fun serverLoopStatus(run: String, status: String): String = envelope("update_loop_status") {
            put("loop_status", Json.parseToJsonElement("""{"status":"$status","active_run_ids":["$run"]}"""))
        }

        /** What the App Server says when no turn runs on the runtime: waiting for input, no active run. */
        fun idleLoopStatus(): String = envelope("update_loop_status") {
            put("loop_status", Json.parseToJsonElement("""{"status":"WAITING_ON_INPUT","active_run_ids":[]}"""))
        }

        /** A loop status [status] that names no active run. */
        fun runlessLoopStatus(status: String): String = envelope("update_loop_status") {
            put("loop_status", Json.parseToJsonElement("""{"status":"$status","active_run_ids":[]}"""))
        }

        /** A loop status of another conversation of the same agent. */
        fun otherRuntimeLoopStatus(): String {
            val other = Json.parseToJsonElement(idleLoopStatus()).jsonObject.toMutableMap()
            other["runtime"] = AppServerProtocol.json.encodeToJsonElement(
                com.letta.mobile.data.transport.appserver.AppServerRuntimeScope.serializer(),
                com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(AGENT.value, "local-conv-other"),
            )
            return JsonObject(other).toString()
        }

        /** The result of the busy turn's tool, on its run. */
        fun toolReturn(): String = frame(
            delta(mapOf("message_type" to "tool_return_message", "run_id" to RUN, "type" to "message", "tool_call_id" to "toolu_lost")),
        )

        /** A loop status no App Server sent yet. */
        const val FUTURE_STATUS = "SOME_FUTURE_STATUS"

        /** Past the client relay's 5 s post-turn queue window; bounded, because the relay's silence timer never idles. */
        const val POST_TURN_WINDOW_SLACK_MS = 10_000L

        /** Slack past the bound for the relay's periodic silence check (it runs every 30 s). */
        const val SILENCE_CHECK_SLACK_MS = 60_000L

        fun decode(raw: String): AppServerReceivedFrame = AppServerProtocol.decodeFrame(raw, AppServerChannel.Stream)

        fun turnFinished(run: String = RUN): AppServerReceivedFrame = decode(
            envelope("turn_finished") {
                put("turn_id", JsonPrimitive("turn-4d1c"))
                put("stop_reason", JsonPrimitive("end_turn"))
                put("run_id", JsonPrimitive(run))
            },
        )

        /** What the App Server sends for a turn it starts on a `<task-notification>`; no client input precedes it. */
        fun serverStartedTurn(): List<AppServerReceivedFrame> = listOf(
            frame(
                delta(
                    mapOf(
                        "type" to "message", "id" to "user-msg-5183a9d1", "date" to "2026-11-26T01:23:17.050Z",
                        "message_type" to "user_message",
                        "content" to "<task-notification>\n<task-id>bash_796</task-id>\n<status>completed</status>\n</task-notification>",
                    ),
                ),
            ),
            assistant(REPLY),
            frame(
                delta(
                    mapOf(
                        "id" to "letta-msg-16120", "date" to "2026-11-26T01:23:39.968Z", "agent_id" to AGENT.value,
                        "conversation_id" to CONVERSATION.value, "message_type" to "usage_statistics", "run_id" to RUN,
                        "type" to "message",
                    ),
                ),
            ),
            stop(RUN),
        ).map(::decode) + turnFinished()

        fun childUsage() = decode(
            frame(
                delta(
                    mapOf(
                        "type" to "message", "id" to "letta-msg-184", "agent_id" to CHILD, "conversation_id" to "default",
                        "message_type" to "usage_statistics", "run_id" to "local-run-86", "session_id" to CHILD,
                    ),
                ),
            ),
        )

        fun childStop() = decode(stop("local-run-86", reason = "requires_approval", session = CHILD))

        fun childSubagentDelta() = decode(
            frame(
                delta(mapOf("type" to "message", "message_type" to "assistant_message", "run_id" to "local-run-86")),
                subagent = "task_43",
            ),
        )
    }
}
