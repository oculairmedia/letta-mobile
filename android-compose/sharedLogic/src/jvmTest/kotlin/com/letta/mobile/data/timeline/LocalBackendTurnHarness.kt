package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.controller.node.iroh.LocalBackendMessageProjection
import com.letta.mobile.data.controller.node.iroh.LocalBackendStoreSupport
import com.letta.mobile.data.controller.node.iroh.MessageListWireProjection
import com.letta.mobile.data.controller.node.iroh.MessageSidecars
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A LocalBackend transcript taken through the production route: the wrapper's
 * [LocalBackendMessageProjection] and [MessageListWireProjection], then the canonical engine's
 * live block (streamed) and its cold ledger page (settled). Shared by the projection tests that
 * compare what the two paths render for one real-shaped turn.
 */
internal class LocalBackendTurnHarness(name: String) {
    val scope = TimelineScope(name, "conversation")
    val store = InMemoryTimelineStore()

    fun engine() = CanonicalTimelineEngine(
        store, TimelineExactCanonicalWriter(scope, 2_000_000), TimelinePageBudget(128, 8_000_000), enabled = true,
    )

    /** Streams [messages] as one live turn, then settles them through a reconcile page. */
    suspend fun project(messages: List<LettaMessage>): ProjectedTurn {
        val writer = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(writer.open(scope)).selection
        val fence = writer.beginLive(selection)
        messages.forEach { assertTrue(writer.ingest(fence, TimelineStreamFrame.Message(it))) }
        assertTrue(writer.ingest(fence, TimelineStreamFrame.Done))
        val live = renderItems(assertNotNull(writer.live.value).block.events)
        reconcile(writer, selection, messages)
        return ProjectedTurn(live, settled())
    }

    /** Commits [messages] as one reconcile page, the way sync settles a turn. */
    suspend fun settle(messages: List<LettaMessage>) {
        val writer = engine()
        reconcile(writer, assertIs<TimelineEngineOpen.Opened>(writer.open(scope)).selection, messages)
    }

    /** What a cold reader of the ledger renders. */
    suspend fun settled(): List<ChatRenderItem> {
        val cold = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(cold.open(scope)).selection
        val prepared = cold.preparePage(selection, TimelineReadPosition.Tail, 128, null, DefaultTimelineSettledProjectionAdapter)
        return prepared.records.mapNotNull { (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item }
    }

    private suspend fun reconcile(writer: CanonicalTimelineEngine, selection: TimelineEngineSelection, messages: List<LettaMessage>) {
        val request = writer.beginReconcile(selection)
        val page = TimelineRemotePageResult.Page(
            request.remote.requestId, selection.generation,
            messages.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) }, null, false, 0,
        )
        assertEquals(TimelineEnginePageOutcome.Applied, writer.reconcilePage(request, page))
    }

    private fun renderItems(events: List<TimelineEvent>) =
        buildChatRenderModel(events.mapNotNull { timelineEventToUiMessage(it) }, ChatDisplayMode.Interactive).renderItems

    companion object {
        private const val BASE_EPOCH_MS = 1_790_051_400_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** The wire `message.list` page the wrapper serves for [transcript]. */
        fun wireMessages(transcript: List<TurnEntry>): List<LettaMessage> = wireJson(transcript)
            .map { json.decodeFromJsonElement(LettaMessage.serializer(), it) }

        fun wireJson(transcript: List<TurnEntry>): List<JsonObject> {
            val projection = LocalBackendMessageProjection(
                LocalBackendStoreSupport(kotlin.io.path.createTempDirectory("turn-harness").toFile(), "http://localhost"),
            )
            val sidecars = MessageSidecars(emptyMap(), emptyMap(), emptyMap(), emptyMap())
            val wire = JsonArray(
                transcript.mapIndexed { index, entry -> entry.toJson(BASE_EPOCH_MS + index * 1_000L) }.flatMap { message ->
                    projection.localMessageToConversationMessages(JsonObject(message + ("parts" to message.getValue("content"))), sidecars)
                },
            )
            return MessageListWireProjection.projectMessageList(wire, "conversation").jsonArray.map { it as JsonObject }
        }

        /** Decodes wire objects after [transform], for tests that need an older wire shape. */
        fun decode(wire: List<JsonObject>, transform: (JsonObject) -> JsonObject): List<LettaMessage> =
            wire.map { json.decodeFromJsonElement(LettaMessage.serializer(), transform(it)) }
    }
}

internal data class ProjectedTurn(val live: List<ChatRenderItem>, val settled: List<ChatRenderItem>)

internal fun ChatRenderItem.members(): List<UiMessage> = when (this) {
    is ChatRenderItem.Single -> listOf(message)
    is ChatRenderItem.RunBlock -> messages.map { it.first }
}

internal sealed interface TurnPart {
    fun toJson(): JsonObject

    data class Text(val text: String) : TurnPart {
        override fun toJson() = buildJsonObject { put("type", "text"); put("text", text) }
    }

    data class Reasoning(val text: String) : TurnPart {
        override fun toJson() = buildJsonObject { put("type", "reasoning"); put("text", text) }
    }

    data class Call(val callId: String, val tool: String) : TurnPart {
        override fun toJson() = buildJsonObject {
            put("type", "toolCall"); put("id", callId); put("name", tool)
            put("arguments", buildJsonObject { put("command", "run $callId") })
        }
    }
}

internal sealed interface TurnEntry {
    val id: String
    val role: String
    val parts: List<TurnPart>
    fun extra(): Map<String, JsonPrimitive> = emptyMap()

    fun toJson(epochMs: Long): JsonObject {
        val iso = java.time.Instant.ofEpochMilli(epochMs).toString()
        val metadata = buildJsonObject {
            put("created_at", iso); put("updated_at", iso); put("agent_id", "agent-1"); put("conversation_id", "conv-1")
        }
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive(id), "role" to JsonPrimitive(role),
                "content" to JsonArray(parts.map(TurnPart::toJson)),
                "timestamp" to JsonPrimitive(epochMs), "metadata" to metadata,
            ) + extra(),
        )
    }

    data class User(override val id: String, val text: String) : TurnEntry {
        override val role = "user"
        override val parts = listOf(TurnPart.Text(text))
    }

    data class Assistant(override val id: String, override val parts: List<TurnPart>) : TurnEntry {
        override val role = "assistant"
    }

    data class ToolResult(override val id: String, val callId: String, val tool: String) : TurnEntry {
        override val role = "toolResult"
        override val parts = listOf(TurnPart.Text("ok $callId"))
        override fun extra() = mapOf(
            "toolCallId" to JsonPrimitive(callId), "toolName" to JsonPrimitive(tool), "isError" to JsonPrimitive(false),
        )
    }
}
