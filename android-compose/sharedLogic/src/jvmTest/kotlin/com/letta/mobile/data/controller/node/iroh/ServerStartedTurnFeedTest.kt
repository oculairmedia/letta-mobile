package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.AGENT
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.CONVERSATION
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.REPLY
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.RUN
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.RUNTIME_SCOPE
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.SILENCE_CHECK_SLACK_MS
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.assistant
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.childSubagentDelta
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.decode
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.delta
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.envelope
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.frame
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.serverLoopStatus
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.serverStartedTurn
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.stop
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.turnFinished
import com.letta.mobile.data.controller.node.iroh.feed.FeedJournalStore
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
import kotlinx.serialization.json.JsonPrimitive
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
    /** A client turn of the conversation, with [CLIENT_TURN] as its prompt's client message id, relaying what its engine hands it. */
    private class ClientTurn(val job: Job, val engine: kotlinx.coroutines.channels.Channel<String>, val finish: CompletableDeferred<Unit>)

    private fun TestScope.clientTurn(node: ServerStartedTurnNode, viewers: () -> Set<ViewerHandle> = { emptySet() }): ClientTurn {
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
    private suspend fun ServerStartedTurnNode.sendToBoth(client: ClientTurn, raw: String) {
        frames.emit(decode(raw))
        client.engine.send(raw)
    }

    private suspend fun TestScope.finishedClientTurn(node: ServerStartedTurnNode, relay: List<String>) {
        val client = clientTurn(node)
        relay.forEach { client.engine.send(it) }
        runCurrent()
        client.finish.complete(Unit)
        client.job.join()
    }

    @Test
    fun aTurnTheServerStartsAfterTheClientTurnClosedReachesTheFeedAndTheViewers() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
        finishedClientTurn(node, listOf(assistant("Still running: task_42.", run = "local-run-1206")))
        viewer.frames.clear()

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        val serverTurn = journalEntries(node.journal).filter { it.turn != CLIENT_TURN }
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
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }

        listOf(childUsage(), childStop(), childSubagentDelta(), turnFinished()).forEach { node.frames.emit(it) }
        runCurrent()

        assertTrue(journalEntries(node.journal).isEmpty(), "nothing of a child agent is journaled: ${journalEntries(node.journal)}")
        assertTrue(viewer.frames.isEmpty(), "nothing of a child agent reaches a viewer: ${viewer.frames}")
    }

    @Test
    fun framesOfARunningClientTurnAreRelayedOnceByThatTurn() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
        val client = clientTurn(node) { setOf(viewer) }
        runCurrent()

        clientRun().forEach { node.sendToBoth(client, it) }
        runCurrent()
        client.finish.complete(Unit)
        client.job.join()

        assertEquals(setOf(CLIENT_TURN), journalEntries(node.journal).map { it.turn }.toSet(), "one turn, the client's")
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.kinds(), "one copy of each frame")
    }

    @Test
    fun lateFramesOfAFinishedClientRunOpenNoServerTurn() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
        finishedClientTurn(node, listOf(assistant("Done.", run = CLIENT_RUN)))
        viewer.frames.clear()

        node.frames.emit(decode(assistant("Done. And more.", run = CLIENT_RUN)))
        runCurrent()

        assertTrue(journalEntries(node.journal).none { it.turn != CLIENT_TURN }, "the client run's late frame is not a new turn")
        assertTrue(viewer.frames.isEmpty())
    }

    /**
     * A client sends while a server-started turn runs. The App Server queues the input behind it, so the server
     * turn's remaining frames belong to the server-started turn, which keeps them; the client's own run starts after
     * the server's `turn_finished` and belongs to the client turn. Each frame reaches the feed and the viewer once.
     */
    @Test
    fun aClientTurnStartingDuringAServerStartedTurnLeavesItsFramesToItAndKeepsItsOwn() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
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

        val byTurn = journalEntries(node.journal)
            .filter { it.kind == "stream_delta" }
            .groupBy({ it.turn }) { it.payload?.text("message_type") }
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
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
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

        val journal = journalEntries(node.journal)
        val serverTurn = journal.first { it.turn != CLIENT_TURN }.turn
        assertEquals("turn_closed", journal.last { it.turn == serverTurn }.kind)
        assertEquals("false", journal.last { it.turn == serverTurn }.payload?.get("terminal_observed").toString(), "no terminal was seen")
        assertEquals(listOf("assistant_message", "stop_reason"), journal.deltaKinds(CLIENT_TURN))
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.frames.map { it.delta().text("message_type") })
    }

    /** A turn whose `turn_finished` was lost and whose runtime then sends nothing still closes on the bound, on a timer. */
    @Test
    fun aSilentServerStartedTurnClosesOnTheBoundWithoutAnotherFrame() = runTest {
        val node = startedTurnNode { emptySet() }
        serverStartedTurn().dropLast(2).forEach { node.frames.emit(it) }
        runCurrent()

        advanceTimeBy(SERVER_TURN_SILENCE_MS + SILENCE_CHECK_SLACK_MS)
        runCurrent()

        val closed = journalEntries(node.journal).lastOrNull()
        assertEquals("turn_closed", closed?.kind, "the turn closed with no further frame: ${journalEntries(node.journal).map { it.kind }}")
        assertEquals("false", closed?.payload?.get("terminal_observed").toString())
    }

    /**
     * A server-started turn runs one client-side tool for longer than the bound. The App Server says so
     * (`update_loop_status` EXECUTING_CLIENT_SIDE_TOOL) and then sends the tool's result on the same run: the run stays
     * one turn, closed by the server's own `turn_finished`, with every frame once.
     */
    @Test
    fun aLongToolDoesNotSplitAServerStartedRun() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
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

        val journal = journalEntries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "one turn: ${journal.map { it.kind }}")
        assertEquals(1, journal.count { it.kind == "turn_closed" })
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
        assertEquals(
            listOf("user_message", "tool_call_message", "tool_return_message", "stop_reason"),
            viewer.kinds(),
            "every frame once",
        )
    }

    /** A later frame on the open turn's own run proves it alive even after a quiet stretch with no loop status. */
    @Test
    fun aFrameOnTheOpenRunAfterSilenceKeepsTheTurn() = runTest {
        val node = startedTurnNode { emptySet() }
        serverStartedTurn().take(2).forEach { node.frames.emit(it) }
        runCurrent()

        node.nowMs += 2 * SERVER_TURN_SILENCE_MS
        listOf(decode(stop(RUN)), turnFinished()).forEach { node.frames.emit(it) }
        runCurrent()

        val journal = journalEntries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /** The same, when the client's run is bound by `update_loop_status` before the silence bound passes. */
    @Test
    fun aClientRunBoundWhileAServerStartedTurnLostItsFinishGoesToTheClientTurn() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
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

        assertEquals(listOf("assistant_message", "stop_reason"), journalEntries(node.journal).deltaKinds(CLIENT_TURN))
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.frames.map { it.delta().text("message_type") })
    }

    /** A turn the server starts right after a client turn (inside its post-turn queue window) is relayed once. */
    @Test
    fun aServerStartedTurnRightAfterAClientTurnIsRelayedOnce() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
        finishedClientTurn(node, clientRun())
        viewer.frames.clear()

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        assertEquals(SERVER_KINDS, viewer.kinds())
        assertEquals(1, journalEntries(node.journal).count { it.kind == "turn_open" && it.turn != CLIENT_TURN })
    }

    /**
     * The real client relay: relayTurn with its ServerFrameTap, whose post-turn window (ServerFrameTap.relayQueueAfterTurn)
     * forwards only `update_queue` to the initiator and stops at the next turn's first stream delta. A turn the server starts
     * inside that window reaches the phone once, through the node's relay, and the tap forwards none of its deltas.
     */
    @Test
    fun aServerStartedTurnInsideTheClientsPostTurnQueueWindowIsRelayedOnce() = runTest {
        val phoneViewer = ServerStartedTurnViewer("observer")
        val node = startedTurnNode { setOf(phoneViewer) }
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
        assertEquals(1, journalEntries(node.journal).count { it.kind == "turn_open" && it.turn != CLIENT_TURN })
    }

    /** A real server-started turn the App Server ran on a `<task-notification>` (feed journal of local-conv-95806538, text redacted). */
    @Test
    fun aCapturedServerStartedTurnReachesTheFeedInFull() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }
        val captured = resourceLines("/appserver/server-started-turn-deltas.jsonl")

        captured.forEach { node.frames.emit(decode(frame(it))) }
        node.frames.emit(turnFinished())
        runCurrent()

        val journaled = journalEntries(node.journal).filter { it.kind == "stream_delta" }.map { it.payload?.text("message_type") }
        val expected = captured.map { Json.parseToJsonElement(it).jsonObject.text("message_type") }
        assertEquals(expected, journaled)
        assertEquals("turn_closed", journalEntries(node.journal).last().kind)
        assertTrue(viewer.frames.isNotEmpty())
    }

    @Test
    fun aViewerThatConnectsLaterReplaysTheServerStartedTurnFromTheFeed() = runTest {
        val node = startedTurnNode { emptySet() }

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        // feed.subscribe without a cursor serves exactly this page (FeedRoutes over FeedJournalStore.page).
        val replay = journalEntries(node.journal)
        assertEquals(FULL_TURN, replay.map { it.kind })
        assertEquals(REPLY, replay[2].payload?.text("content"))
    }

    /** A delta whose run_id is blank is a frame with no run, never a crash of the relay every conversation shares. */
    @Test
    fun aBlankRunIdIsNoRunAndTheRelayKeepsGoing() = runTest {
        val viewer = ServerStartedTurnViewer("phone")
        val node = startedTurnNode { setOf(viewer) }

        node.frames.emit(decode(frame(delta(mapOf("message_type" to "assistant_message", "run_id" to "", "content" to "blank")))))
        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()

        val kinds = viewer.frames.map { it.delta().text("message_type") }
        assertEquals(listOf("assistant_message", "user_message", "assistant_message", "usage_statistics", "stop_reason"), kinds)
        assertEquals("turn_closed", journalEntries(node.journal).last().kind)
    }

    /**
     * The claim both sides make is one decision: whichever side claims a frame first, the frame is relayed exactly
     * once, and a server-started run's frame is never refused by both (it would then reach nobody).
     */
    @Test
    fun eachFrameIsClaimedByExactlyOneSideInEitherOrder() = runTest {
        val node = startedTurnNode { emptySet() }
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
        val viewer = ServerStartedTurnViewer("phone")
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

        assertEquals(FULL_TURN, journalEntries(journal).map { it.kind })
        assertEquals(4, viewer.frames.size)
    }

    private fun resourceLines(path: String): List<String> =
        requireNotNull(javaClass.getResource(path)).readText().lines().filter { it.isNotBlank() }

    private companion object {
        const val CHILD = "agent-local-2cf9d01d-2328-4145-9779-7127351c2961"
        const val CLIENT_TURN = "e39d7e74-e18c-406f-8045-c15ec1ef31ff"
        const val CLIENT_RUN = "local-run-1208"
        val SERVER_KINDS = listOf("user_message", "assistant_message", "usage_statistics", "stop_reason")
        val FULL_TURN = listOf("turn_open", "stream_delta", "stream_delta", "stream_delta", "stream_delta", "turn_closed")

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

        /** Past the client relay's 5 s post-turn queue window; bounded, because the relay's silence timer never idles. */
        const val POST_TURN_WINDOW_SLACK_MS = 10_000L

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
    }
}
