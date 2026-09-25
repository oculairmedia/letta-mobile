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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-jqiu3 (AC2, projection half): a model turn segmented around its tool calls yields
 * whitespace-only text segments. Those segments must not become render items on either the live
 * or the settled (cold ledger) path, and dropping them must not split the turn's run, which would
 * scatter its tool calls across rows instead of one collapsed group.
 *
 * The transcript goes through the same LocalBackend -> wire -> canonical engine route as
 * [ExactToolTurnsTest], so the settled rows are the ones the paged timeline presents.
 */
class ContentlessSegmentProjectionTest {
    @Test fun whitespaceSegmentsBecomeNoRenderItemsOnTheSettledPath() = runTest {
        val projected = projectTurn(realShapedTurn())

        assertNoContentlessRows(projected.settled)
        assertEquals(listOf(4), toolCallsPerRun(projected.settled), "the run must stay one aggregate")
        assertEquals(TURN_TEXT, visibleText(projected.settled))
    }

    @Test fun whitespaceSegmentsBecomeNoRenderItemsOnTheLivePath() = runTest {
        val projected = projectTurn(realShapedTurn())

        assertNoContentlessRows(projected.live)
        assertEquals(listOf(4), toolCallsPerRun(projected.live))
        assertEquals(TURN_TEXT, visibleText(projected.live))
    }

    private fun assertNoContentlessRows(items: List<ChatRenderItem>) {
        val blank = items.flatMap(::messagesOf).filter { it.isAssistantWithoutContent() }
        assertTrue(blank.isEmpty(), "zero-content render members: ${blank.map { it.id }}")
    }

    private fun UiMessage.isAssistantWithoutContent() =
        role == "assistant" && !isReasoning && content.isBlank() && toolCalls.isNullOrEmpty()

    private fun toolCallsPerRun(items: List<ChatRenderItem>) = items.filterIsInstance<ChatRenderItem.RunBlock>()
        .map { block -> block.messages.sumOf { it.first.toolCalls.orEmpty().size } }

    private fun visibleText(items: List<ChatRenderItem>) = items.flatMap(::messagesOf)
        .filter { it.role == "assistant" && !it.isReasoning && it.content.isNotBlank() }
        .map { it.content }
        .toSet()

    private fun messagesOf(item: ChatRenderItem): List<UiMessage> = when (item) {
        is ChatRenderItem.Single -> listOf(item.message)
        is ChatRenderItem.RunBlock -> item.messages.map { it.first }
    }

    /** text, tool card, whitespace, tool card, reasoning, whitespace, tool card, text. */
    private fun realShapedTurn(): List<TranscriptEntry> = listOf(
        TranscriptEntry.User("u1", "please fix the spacing"),
        TranscriptEntry.Assistant("a1", listOf(Part.Text("I'll inspect the files."), Part.Call("c1", "Edit"), Part.Call("c2", "Edit"))),
        TranscriptEntry.ToolResult("r1", "c1", "Edit"),
        TranscriptEntry.ToolResult("r2", "c2", "Edit"),
        TranscriptEntry.Assistant("a2", listOf(Part.Text("\n\n"), Part.Call("c3", "Bash"))),
        TranscriptEntry.ToolResult("r3", "c3", "Bash"),
        TranscriptEntry.Assistant("a3", listOf(Part.Reasoning("Verify the edit"))),
        TranscriptEntry.Assistant("a4", listOf(Part.Text(" "), Part.Call("c4", "Bash"))),
        TranscriptEntry.ToolResult("r4", "c4", "Bash"),
        TranscriptEntry.Assistant("a5", listOf(Part.Text("All fixed."))),
    )

    private data class ProjectedTurn(val live: List<ChatRenderItem>, val settled: List<ChatRenderItem>)

    private suspend fun projectTurn(transcript: List<TranscriptEntry>): ProjectedTurn {
        val messages = wireMessages(transcript)
        val scope = TimelineScope("jqiu3", "conversation")
        val store = InMemoryTimelineStore()
        fun engine() = CanonicalTimelineEngine(
            store, TimelineExactCanonicalWriter(scope, 2_000_000), TimelinePageBudget(128, 8_000_000), enabled = true,
        )
        val writer = engine()
        val selection = assertIs<TimelineEngineOpen.Opened>(writer.open(scope)).selection
        val fence = writer.beginLive(selection)
        messages.forEach { assertTrue(writer.ingest(fence, TimelineStreamFrame.Message(it))) }
        assertTrue(writer.ingest(fence, TimelineStreamFrame.Done))
        val live = buildChatRenderModel(
            assertNotNull(writer.live.value).block.events.mapNotNull { timelineEventToUiMessage(it) },
            ChatDisplayMode.Interactive,
        ).renderItems
        val request = writer.beginReconcile(selection)
        val page = TimelineRemotePageResult.Page(
            request.remote.requestId, selection.generation,
            messages.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) }, null, false, 0,
        )
        assertEquals(TimelineEnginePageOutcome.Applied, writer.reconcilePage(request, page))
        val cold = engine()
        val coldSelection = assertIs<TimelineEngineOpen.Opened>(cold.open(scope)).selection
        val prepared = cold.preparePage(coldSelection, TimelineReadPosition.Tail, 128, null, DefaultTimelineSettledProjectionAdapter)
        val settled = prepared.records.mapNotNull { (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item }
        return ProjectedTurn(live, settled)
    }

    private fun wireMessages(transcript: List<TranscriptEntry>): List<LettaMessage> {
        val projection = LocalBackendMessageProjection(
            LocalBackendStoreSupport(kotlin.io.path.createTempDirectory("jqiu3").toFile(), "http://localhost"),
        )
        val sidecars = MessageSidecars(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val wire = JsonArray(
            transcript.mapIndexed { index, entry -> entry.toJson(BASE_EPOCH_MS + index * 1_000L) }.flatMap { message ->
                projection.localMessageToConversationMessages(JsonObject(message + ("parts" to message.getValue("content"))), sidecars)
            },
        )
        return MessageListWireProjection.projectMessageList(wire, "conversation").jsonArray
            .map { json.decodeFromJsonElement(LettaMessage.serializer(), it) }
    }

    private sealed interface Part {
        fun toJson(): JsonObject

        data class Text(val text: String) : Part {
            override fun toJson() = buildJsonObject { put("type", "text"); put("text", text) }
        }

        data class Reasoning(val text: String) : Part {
            override fun toJson() = buildJsonObject { put("type", "reasoning"); put("text", text) }
        }

        data class Call(val callId: String, val tool: String) : Part {
            override fun toJson() = buildJsonObject {
                put("type", "toolCall"); put("id", callId); put("name", tool)
                put("arguments", buildJsonObject { put("command", "run $callId") })
            }
        }
    }

    private sealed interface TranscriptEntry {
        val id: String
        val role: String
        val parts: List<Part>
        fun extra(): Map<String, JsonPrimitive> = emptyMap()

        fun toJson(epochMs: Long): JsonObject {
            val iso = java.time.Instant.ofEpochMilli(epochMs).toString()
            val metadata = buildJsonObject {
                put("created_at", iso); put("updated_at", iso); put("agent_id", "agent-1"); put("conversation_id", "conv-1")
            }
            return JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id), "role" to JsonPrimitive(role),
                    "content" to JsonArray(parts.map(Part::toJson)),
                    "timestamp" to JsonPrimitive(epochMs), "metadata" to metadata,
                ) + extra(),
            )
        }

        data class User(override val id: String, val text: String) : TranscriptEntry {
            override val role = "user"
            override val parts = listOf(Part.Text(text))
        }

        data class Assistant(override val id: String, override val parts: List<Part>) : TranscriptEntry {
            override val role = "assistant"
        }

        data class ToolResult(override val id: String, val callId: String, val tool: String) : TranscriptEntry {
            override val role = "toolResult"
            override val parts = listOf(Part.Text("ok $callId"))
            override fun extra() = mapOf(
                "toolCallId" to JsonPrimitive(callId), "toolName" to JsonPrimitive(tool), "isError" to JsonPrimitive(false),
            )
        }
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_790_051_400_000L
        val TURN_TEXT = setOf("I'll inspect the files.", "All fixed.")
        val json = Json { ignoreUnknownKeys = true }
    }
}
