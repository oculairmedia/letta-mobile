package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.feed.FeedJournalStore
import com.letta.mobile.data.controller.node.iroh.feed.JournalKey
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** A viewer of the conversation's feed that records every frame written to it. */
internal class ServerStartedTurnViewer(override val connectionId: String) : ViewerHandle {
    val frames = mutableListOf<String>()
    override suspend fun writeFrame(frame: String): Boolean {
        frames += frame
        return true
    }
}

/** A node's turn host, its feed journal and the App Server frames its standing server-started relay reads. */
internal class ServerStartedTurnNode(
    val host: NodeTurnHost,
    val journal: FeedJournalStore,
    val frames: MutableSharedFlow<AppServerReceivedFrame>,
) {
    var nowMs = 0L
}

/** A [ServerStartedTurnNode] with its server-started relay running, delivering to [viewers]. */
internal fun TestScope.startedTurnNode(viewers: () -> Set<ViewerHandle>): ServerStartedTurnNode {
        val journal = FeedJournalStore()
        val hostScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val host = NodeTurnHost(hostScope, journal)
        val frames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val node = ServerStartedTurnNode(host, journal, frames)
        // The relay's clock is virtual time plus a manual offset, so tests can age a turn with or without running its timer.
        val clock = { testScheduler.currentTime + node.nowMs }
        host.relayServerStartedTurns(frames, TurnFanoutDelivery(viewersFor = { viewers() }), nowMs = clock)
        runCurrent()
        return node
    return node
}

/** Every entry the conversation's feed journal holds. */
internal fun journalEntries(journal: FeedJournalStore): List<FeedDelta> =
    journal.page(JournalKey.of(ServerStartedTurnFixtures.AGENT, ServerStartedTurnFixtures.CONVERSATION), from = null, limit = 200).entries

/** The `message_type` of every frame this viewer was sent. */
internal fun ServerStartedTurnViewer.kinds(): List<String?> = frames.map { it.delta().text("message_type") }

/** The `message_type` of every stream delta journaled for [turn]. */
internal fun List<FeedDelta>.deltaKinds(turn: String): List<String?> =
    filter { it.turn == turn && it.kind == "stream_delta" }.map { it.payload?.text("message_type") }

/** The `delta` object of a stream_delta frame. */
internal fun String.delta(): JsonObject = AppServerProtocol.json.parseToJsonElement(this).jsonObject.getValue("delta").jsonObject

/** The string value of [key], or null when it is absent or not a primitive. */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * The App Server frames the server-started turn tests share, shaped like the ones the App Server sent for
 * conversation local-conv-95806538.
 */
internal object ServerStartedTurnFixtures {
    val AGENT = AgentId("agent-c356b54a-8b37-4d53-b9d0-b43164749b6f")
    val CONVERSATION = ConversationId("local-conv-95806538")
    const val RUN = "local-run-1207"
    val RUNTIME_SCOPE = AppServerRuntimeScope(AGENT.value, CONVERSATION.value)
    const val REPLY = "task_42 finished; dispatching the review."
    private val RUNTIME_JSON = AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), RUNTIME_SCOPE)
    private var seq = 4000L

    /** Slack past the bound for the relay's periodic silence check (it runs every 30 s). */
    const val SILENCE_CHECK_SLACK_MS = 60_000L

    /** One App Server frame of [type] for the conversation, in the envelope the App Server sends. */
    fun envelope(type: String, fields: JsonObjectBuilder.() -> Unit): String {
        seq += 1
        return buildJsonObject {
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
        JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()

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

    /** A loop status [status] for [run] with no client prompt bound to it (a turn the server started itself). */
    fun serverLoopStatus(run: String, status: String): String = envelope("update_loop_status") {
        put("loop_status", Json.parseToJsonElement("""{"status":"$status","active_run_ids":["$run"]}"""))
    }

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

    fun childSubagentDelta() = decode(
        frame(
            delta(mapOf("type" to "message", "message_type" to "assistant_message", "run_id" to "local-run-86")),
            subagent = "task_43",
        ),
    )
}
