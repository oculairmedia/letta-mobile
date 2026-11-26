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

    private class Node(val host: NodeTurnHost, val journal: FeedJournalStore, val frames: MutableSharedFlow<AppServerReceivedFrame>)

    private fun TestScope.node(viewers: () -> Set<ViewerHandle>): Node {
        val journal = FeedJournalStore()
        val host = NodeTurnHost(CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])), journal)
        val frames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        host.relayServerStartedTurns(frames, TurnFanoutDelivery(viewersFor = { viewers() }))
        runCurrent()
        return Node(host, journal, frames)
    }

    /** A client turn of the conversation that relays [relay] and ends when [finish] completes. */
    private fun TestScope.clientTurn(node: Node, finish: CompletableDeferred<Unit>, relay: List<String> = emptyList()): Job {
        val writer = node.journal.openTurn(AGENT, CONVERSATION, CLIENT_TURN)
        val fanout = ConversationTurnFanout(
            conversationId = CONVERSATION.value,
            runtime = com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(AGENT.value, CONVERSATION.value),
            delivery = TurnFanoutDelivery(viewersFor = { emptySet() }),
            initiatorViewer = null,
            trackInitiatorFrame = writer::append,
            turnId = CLIENT_TURN,
        )
        return launch {
            node.host.run(
                NodeTurn(CONVERSATION.value, CLIENT_TURN, fanout, writer) {
                    relay.forEach { fanout.onDraft(RuntimeEventPayload.RemoteStreamFrame(frameId = it, body = it)) }
                    finish.await()
                },
            )
        }
    }

    @Test
    fun aTurnTheServerStartsAfterTheClientTurnClosedReachesTheFeedAndTheViewers() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        val finish = CompletableDeferred<Unit>()
        val client = clientTurn(node, finish, relay = listOf(assistant("Still running: task_42.", run = "local-run-1206")))
        runCurrent()
        finish.complete(Unit)
        client.join()
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
        val finish = CompletableDeferred<Unit>()
        val client = clientTurn(node, finish)
        runCurrent()

        serverStartedTurn().forEach { node.frames.emit(it) }
        runCurrent()
        finish.complete(Unit)
        client.join()

        assertTrue(entries(node.journal).none { it.turn != CLIENT_TURN }, "no second turn beside the client's: ${entries(node.journal)}")
        assertTrue(viewer.frames.isEmpty(), "the client turn's own relay is the only copy")
    }

    @Test
    fun lateFramesOfAFinishedClientRunOpenNoServerTurn() = runTest {
        val viewer = RecordingViewer("phone")
        val node = node { setOf(viewer) }
        val finish = CompletableDeferred<Unit>()
        val client = clientTurn(node, finish, relay = listOf(assistant("Done.", run = "local-run-1206")))
        runCurrent()
        finish.complete(Unit)
        client.join()

        node.frames.emit(decode(assistant("Done. And more.", run = "local-run-1206")))
        runCurrent()

        assertTrue(entries(node.journal).none { it.turn != CLIENT_TURN }, "the client run's late frame is not a new turn")
        assertTrue(viewer.frames.isEmpty())
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
