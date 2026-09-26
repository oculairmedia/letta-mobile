package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.*
import com.letta.mobile.data.controller.node.iroh.*
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ExactToolTurnsTest {
    @Test fun exactProductionTurns() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val fixture = checkNotNull(javaClass.getResource("/exact-tool-turns.json")) { "missing exact tool-turn fixture" }
        val source = json.parseToJsonElement(fixture.readText()).jsonArray
            .map { it.jsonObject.getValue("message").jsonObject }
        val projection = LocalBackendMessageProjection(
            LocalBackendStoreSupport(kotlin.io.path.createTempDirectory("exact-tool-turns").toFile(), "http://localhost"),
        )
        val sidecars = MessageSidecars(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        for ((range, expectedCalls) in listOf((9171034..9171049) to 7, (9171082..9171098) to 6)) {
            val raw = source.filter { it.getValue("id").jsonPrimitive.content.removePrefix("ui-msg-").toInt() in range }
                .filterNot { it.getValue("id").jsonPrimitive.content == "ui-msg-9171090" }
            // LocalBackend persists transcript content blocks as parts; preserve every block verbatim.
            fun bounded(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> JsonObject(value.mapValues { (_, v) -> bounded(v) })
                is JsonArray -> JsonArray(value.map(::bounded))
                is JsonPrimitive -> if (value.isString && value.content.length > 4000 &&
                    !value.content.contains("<skill_content") && !value.content.contains("<task-notification"))
                    JsonPrimitive(value.content.take(2000)) else value
            }
            val wire = JsonArray(raw.flatMap { original ->
                val message = bounded(original).jsonObject
                projection.localMessageToConversationMessages(JsonObject(message + ("parts" to message.getValue("content"))), sidecars)
            })
            val messages = MessageListWireProjection.projectMessageList(wire, "conversation").jsonArray
                .map { json.decodeFromJsonElement(LettaMessage.serializer(), it) }
            val scope = TimelineScope("exact", "conversation")
            val store = InMemoryTimelineStore()
            fun engine() = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 2_000_000),
                TimelinePageBudget(128, 8_000_000), enabled = true)
            val writer = engine()
            val selection = assertIs<TimelineEngineOpen.Opened>(writer.open(scope)).selection
            val fence = writer.beginLive(selection)
            messages.forEach { assertTrue(writer.ingest(fence, TimelineStreamFrame.Message(it))) }
            assertTrue(writer.ingest(fence, TimelineStreamFrame.Done))
            val live = buildChatRenderModel(assertNotNull(writer.live.value).block.events.mapNotNull {
                timelineEventToUiMessage(it)
            }, ChatDisplayMode.Interactive).renderItems
            val request = writer.beginReconcile(selection)
            assertEquals(TimelineEnginePageOutcome.Applied, writer.reconcilePage(request, TimelineRemotePageResult.Page(
                request.remote.requestId, selection.generation,
                messages.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) }, null, false, 0)))
            val cold = engine()
            val coldSelection = assertIs<TimelineEngineOpen.Opened>(cold.open(scope)).selection
            val page = cold.preparePage(coldSelection, TimelineReadPosition.Tail, 128, null, DefaultTimelineSettledProjectionAdapter)
            val items = page.records.mapNotNull { (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item }
            fun counts(items: List<ChatRenderItem>) = items.filterIsInstance<ChatRenderItem.RunBlock>()
                .map { block -> block.messages.sumOf { it.first.toolCalls.orEmpty().size } }
            println("EXACT ${range.first}: live=${counts(live)} cold=${counts(items)}")
            assertEquals(listOf(expectedCalls), counts(items), "one aggregate per exact turn")
            assertEquals(listOf(expectedCalls), counts(live))
            val block = items.filterIsInstance<ChatRenderItem.RunBlock>().single()
            val liveBlock = live.filterIsInstance<ChatRenderItem.RunBlock>().single()
            // Concurrent calls from one source row share a timestamp; durable keys break ties by ID.
            fun comparable(block: ChatRenderItem.RunBlock) = block.messages.map { it.first }
                .sortedWith(compareBy({ it.timestamp }, { it.id }))
            assertEquals(comparable(liveBlock), comparable(block))
            assertTrue(page.projectionInput.records.mapNotNull { it.event }.all { it.runId == null })
            if (expectedCalls == 7) {
                assertEquals("completed", block.messages.mapNotNull { it.first.subagentNotification }.single().status)
                assertEquals(setOf("Skill", "Agent", "TaskOutput", "exec_command"),
                    block.messages.flatMap { it.first.toolCalls.orEmpty() }.map { it.name }.toSet())
            } else {
                assertTrue(items.filterIsInstance<ChatRenderItem.Single>().any {
                    it.message.id == "ui-msg-9171098" && it.message.role == "user" &&
                        it.message.content == "can we please implement?"
                })
            }
        }
    }
}
