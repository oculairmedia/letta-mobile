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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        val host = NodeTurnHost(CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), journal)
        val frames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val node = Node(host, journal, frames)
        host.relayServerStartedTurns(frames, TurnFanoutDelivery(viewersFor = { viewers() }), nowMs = { node.nowMs })
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
            delivery = TurnFanoutDelivery(viewersFor = { viewers() }, serverTurnOwns = node.host::serverTurnOwns),
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
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.frames.map { it.delta().text("message_type") }, "one copy of each frame")
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
        serverStartedTurn().dropLast(1).forEach { node.frames.emit(it) }
        runCurrent()
        node.nowMs += NodeTurnHost.SERVER_TURN_SILENCE_MS
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
        assertEquals(listOf("assistant_message", "stop_reason"), journal.filter { it.turn == CLIENT_TURN && it.kind == "stream_delta" }.map { it.payload?.text("message_type") })
        assertEquals(listOf("assistant_message", "stop_reason"), viewer.frames.map { it.delta().text("message_type") })
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

        assertEquals(listOf("assistant_message", "stop_reason"), entries(node.journal).filter { it.turn == CLIENT_TURN && it.kind == "stream_delta" }.map { it.payload?.text("message_type") })
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

        assertEquals(listOf("user_message", "assistant_message", "usage_statistics", "stop_reason"), viewer.frames.map { it.delta().text("message_type") })
        assertEquals(1, entries(node.journal).count { it.kind == "turn_open" && it.turn != CLIENT_TURN })
    }

    /** A real server-started turn the App Server ran on a `<task-notification>` (feed journal of local-conv-95806538, text redacted). */
    @Test
    fun aCapturedServerStartedTurnReachesTheFeedInFull() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        val captured = requireNotNull(javaClass.getResource("/appserver/server-started-turn-deltas.jsonl")).readText().lines().filter { it.isNotBlank() }

        captured.forEach { node.frames.emit(decode(frame(it))) }
        node.frames.emit(turnFinished())
        runCurrent()

        val journaled = entries(node.journal).filter { it.kind == "stream_delta" }.map { it.payload?.text("message_type") }
        val expected = captured.map { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject.text("message_type") }
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
        assertEquals(listOf("turn_open", "stream_delta", "stream_delta", "stream_delta", "stream_delta", "turn_closed"), replay.map { it.kind })
        assertEquals(REPLY, replay[2].payload?.text("content"))
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

        assertEquals(listOf("turn_open", "stream_delta", "stream_delta", "stream_delta", "stream_delta", "turn_closed"), entries(journal).map { it.kind })
        assertEquals(4, viewer.frames.size)
    }

    private fun entries(journal: FeedJournalStore): List<FeedDelta> =
        journal.page(JournalKey.of(AGENT, CONVERSATION), from = null, limit = 200).entries

    private fun String.delta(): JsonObject = AppServerProtocol.json.parseToJsonElement(this).jsonObject.getValue("delta").jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val AGENT = AgentId("agent-c356b54a-8b37-4d53-b9d0-b43164749b6f")
        val CONVERSATION = ConversationId("local-conv-95806538")
        const val CHILD = "agent-local-2cf9d01d-2328-4145-9779-7127351c2961"
        const val CLIENT_TURN = "e39d7e74-e18c-406f-8045-c15ec1ef31ff"
        const val RUN = "local-run-1207"
        const val CLIENT_RUN = "local-run-1208"
        val RUNTIME_SCOPE = com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(AGENT.value, CONVERSATION.value)
        const val REPLY = "task_42 finished; dispatching the review."
        private const val RUNTIME = """"runtime":{"agent_id":"agent-c356b54a-8b37-4d53-b9d0-b43164749b6f","conversation_id":"local-conv-95806538"}"""
        private var seq = 4000L

        fun frame(body: String, subagent: String? = null): String {
            seq += 1
            val sub = subagent?.let { ""","subagent_id":"$it"""" }.orEmpty()
            return """{"type":"stream_delta","delta":$body$sub,$RUNTIME,"event_seq":$seq,"emitted_at":"2026-11-26T01:23:17.050Z","idempotency_key":"stream_delta:$seq:k"}"""
        }

        fun assistant(text: String, run: String = RUN) = frame(
            """{"id":"ui-msg-9310564","date":"2026-11-26T01:23:24.617Z","agent_id":"${AGENT.value}","conversation_id":"${CONVERSATION.value}","message_type":"assistant_message","run_id":"$run","seq_id":1,"type":"message","content":"$text"}""",
        )

        /** The client's own run: its reply and its end. */
        fun clientRun(): List<String> = listOf(
            assistant("On it.", run = CLIENT_RUN),
            frame("""{"message_type":"stop_reason","stop_reason":"end_turn","run_id":"$CLIENT_RUN","seq_id":2,"type":"message"}"""),
        )

        fun loopStatus(run: String, clientMessageId: String): String {
            seq += 1
            return """{"type":"update_loop_status","loop_status":{"status":"PROCESSING_API_RESPONSE","active_run_ids":["$run"],"client_message_ids_by_run_id":{"$run":["$clientMessageId"]}},$RUNTIME,"event_seq":$seq,"emitted_at":"2026-11-26T01:24:00Z","idempotency_key":"update_loop_status:$seq:k"}"""
        }

        fun decode(raw: String): AppServerReceivedFrame = AppServerProtocol.decodeFrame(raw, AppServerChannel.Stream)

        fun turnFinished(): AppServerReceivedFrame {
            seq += 1
            return decode("""{"type":"turn_finished","turn_id":"turn-4d1c","stop_reason":"end_turn","run_id":"$RUN",$RUNTIME,"event_seq":$seq,"emitted_at":"2026-11-26T01:23:40Z","idempotency_key":"turn_finished:$seq:k"}""")
        }

        /** What the App Server sends for a turn it starts on a `<task-notification>`; no client input precedes it. */
        fun serverStartedTurn(): List<AppServerReceivedFrame> = listOf(
            frame(
                """{"type":"message","id":"user-msg-5183a9d1","date":"2026-11-26T01:23:17.050Z","message_type":"user_message","content":"<task-notification>\n<task-id>bash_796</task-id>\n<status>completed</status>\n</task-notification>"}""",
            ),
            assistant(REPLY),
            frame(
                """{"id":"letta-msg-16120","date":"2026-11-26T01:23:39.968Z","agent_id":"${AGENT.value}","conversation_id":"${CONVERSATION.value}","message_type":"usage_statistics","step_count":1,"prompt_tokens":455001,"completion_tokens":120,"total_tokens":455121,"run_id":"$RUN","seq_id":2,"type":"message"}""",
            ),
            frame("""{"message_type":"stop_reason","stop_reason":"end_turn","run_id":"$RUN","seq_id":3,"type":"message"}"""),
        ).map(::decode) + turnFinished()

        fun childUsage() = decode(
            frame(
                """{"type":"message","id":"letta-msg-184","agent_id":"$CHILD","conversation_id":"default","message_type":"usage_statistics","step_count":1,"total_tokens":173062,"run_id":"local-run-86","seq_id":2,"session_id":"$CHILD"}""",
            ),
        )

        fun childStop() = decode(
            frame("""{"type":"message","message_type":"stop_reason","stop_reason":"requires_approval","run_id":"local-run-86","seq_id":3,"session_id":"$CHILD"}"""),
        )

        fun childSubagentDelta() = decode(
            frame("""{"type":"message","message_type":"assistant_message","run_id":"local-run-86","content":"child text"}""", subagent = "task_43"),
        )
    }
}
