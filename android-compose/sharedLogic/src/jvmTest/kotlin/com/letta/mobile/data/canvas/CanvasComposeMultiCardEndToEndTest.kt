package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.AGENT
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.CONVERSATION
import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.TOPIC
import com.letta.mobile.data.canvas.compose.CanvasComposeContract
import com.letta.mobile.data.canvas.compose.CanvasComposeIds
import com.letta.mobile.data.canvas.compose.CanvasComposePlacement
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.ComposeStatus
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipts
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaMessageSerializer
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.runtime.ExternalToolDispatcher
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import java.nio.file.Files
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The multi-card end-to-end gate (letta-mobile-bglj6.14; the 8tlf9 gate of the bglj6 review), the
 * part that needs this module's internals: a realistic mixed artifact (`request-multi-card.json`:
 * a heading, a 12-entry checklist, a markdown note with headings and nested lists, a group of four
 * cards, more notes and a caption) asked for by an agent on a board that already holds a drawing
 * and two notes, through the real path:
 *
 * App Server `external_tool_call_request` -> ExternalToolDispatcher -> the Iroh host's
 * canvas_compose -> one atomic BatchOp in the relay log -> the board; the relay entries applied by
 * an app into its notebook (NotebookLocalStore), the app restarted and the board read back; the
 * answer streamed back as the run's tool return -> the timeline reducer -> exactly one receipt on
 * the narrating message, the same after a hydrate from the stored envelope. Then a second compose
 * lands beside the first without overlap, and a retry of the first is idempotent on the board and
 * in the chat.
 *
 * Reserved frames are checked here; the rendered cards (auto-fit inside the reservation, nothing
 * clipped, Show on canvas) are checked on desktop by sharedUI's CanvasComposeEndToEndRenderTest,
 * from the same request.
 */
class CanvasComposeMultiCardEndToEndTest {
    private class RecordingClient : AppServerClient {
        val responses = mutableListOf<AppServerCommand.ExternalToolCallResponse>()
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
            responses += command
        }
    }

    /** One compose call as the App Server sends it: its request id, tool call id and input. */
    private data class ComposeCall(val requestId: String, val toolCallId: String, val input: String)

    /** A compose call's answer: its text and whether it is an error. */
    private data class Answer(val text: String, val isError: Boolean)

    private val request: String = checkNotNull(javaClass.getResource("/canvas/compose/v1/request-multi-card.json")).readText()

    private val host = HostCanvasComposeToolsTest.Host()
    private val client = RecordingClient()
    private val dispatcher = ExternalToolDispatcher(
        client = client,
        externalToolRegistry = host.registry,
        inboundControlRegistry = InboundControlRequestRegistry(),
        connectionGenerationProvider = { 0L },
    )

    /** [call] answered by the real dispatcher. */
    private suspend fun dispatch(call: ComposeCall): Answer {
        dispatcher.answer(
            AppServerInboundFrame.ExternalToolCallRequest(
                requestId = call.requestId,
                runtime = AppServerRuntimeScope(agentId = AGENT, conversationId = CONVERSATION),
                toolCallId = call.toolCallId,
                toolName = CanvasToolContract.COMPOSE,
                input = json.parseToJsonElement(call.input).jsonObject,
            ),
            leaseToken = 1L,
            validatedGeneration = 0L,
        )
        val response = client.responses.last()
        assertEquals(call.requestId, response.requestId)
        val result = assertNotNull(response.result)
        return Answer(result.content.single().text.orEmpty(), result.isError == true)
    }

    private fun receiptOf(answer: Answer): ComposeReceipt {
        assertTrue(!answer.isError, "refused: ${answer.text}")
        return CanvasComposeContract.json.decodeFromString(ComposeReceipt.serializer(), answer.text)
    }

    /** A drawing and two notes a person made before the agent composed anything. */
    private suspend fun drawAndWriteFirst() {
        fun note(text: String) =
            """{"version":2,"blocks":[{"id":"b1","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"$text","spans":[]}}]}"""
        val ops = buildJsonArray {
            addJsonObject {
                put("type", "add_element"); put("elementId", "sketch")
                put("elementJson", """{"type":"Shape","shapeType":"CIRCLE","points":["80.0,80.0","560.0,400.0"],"strokeColor":"#1f2937ff","strokeWidth":3.0,"zIndex":0}""")
            }
            addJsonObject {
                put("type", "set_document"); put("documentId", "note-a"); put("documentJson", note("Ideas for May"))
                put("frame", buildJsonObject { put("x", 80); put("y", 440); put("width", 300); put("height", 200) })
            }
            addJsonObject {
                put("type", "set_document"); put("documentId", "note-b"); put("documentJson", note("Ask Ana about the flat"))
                put("frame", buildJsonObject { put("x", 420); put("y", 440); put("width", 300); put("height", 260) })
            }
        }
        val result = host.registry.invoke(
            CanvasToolContract.APPLY_OPS, buildJsonObject { put("ops", ops) }, agentId = AGENT, conversationId = CONVERSATION,
        )
        assertIs<ExternalToolResult.Success>(result, "$result")
    }

    @Test
    fun aMixedArtifactGoesFromTheAgentToTheBoardTheNotebookAndOneReceiptCard() = runBlocking {
        drawAndWriteFirst()
        val beforeEntries = host.logged().size
        val existing = Board(host.scene().sceneJson).rects(artifactId = null).values.toList()
        assertEquals(3, existing.size)

        // --- the agent composes, through the dispatcher, onto the host ------------------------
        val first = ComposeCall("req-1", CALL_1, request)
        val answer = dispatch(first)
        val receipt = receiptOf(answer)
        assertTheReceiptNamesEveryPiece(receipt, answer)

        // One atomic entry in the relay log: the whole artifact as one batch.
        val entries = host.logged()
        assertEquals(beforeEntries + 1, entries.size, "the artifact is one log entry")
        assertEquals(AGENT, assertIs<CanvasOp.BatchOp>(entries.last().op).actorId)

        // The board: every piece there, notes AUTO with provenance, nothing over anything else.
        val scene = host.scene()
        assertEquals(receipt.revision, scene.revision)
        val board = Board(scene.sceneJson)
        assertThePiecesAreOnTheBoard(board, receipt)
        assertLaidOutCleanly(board, receipt, existing)

        // --- an app takes the relay log into its notebook, restarts, and reads the board back --
        val reloaded = Board(reloadedThroughANotebook())
        assertSameBoard(board, reloaded)
        assertLaidOutCleanly(reloaded, receipt, existing)

        // --- the run's frames: one receipt card, on the narrating message ---------------------
        assertOneCardOnTheNarration(timeline(first, answer), receipt, reloaded)

        // --- a second compose lands beside the first, overlapping nothing ---------------------
        val afterSecond = assertASecondComposeLandsBeside(reloaded, receipt, existing)

        // --- a retry of the first is idempotent: nothing written, the same receipt, one card --
        assertARetryIsIdempotent(first, Answered(answer, receipt), afterSecond)
    }

    /** A call's answer and the receipt read from it. */
    private class Answered(val answer: Answer, val receipt: ComposeReceipt)

    private fun assertTheReceiptNamesEveryPiece(receipt: ComposeReceipt, answer: Answer) {
        assertEquals(ComposeStatus.PUBLISHED, receipt.status)
        assertEquals("lisbon-trip", receipt.artifactId)
        val pieces = piecesOf(receipt)
        assertEquals(12, pieces.size)
        assertEquals(
            setOf(ComposeKind.TEXT, ComposeKind.CHECKLIST, ComposeKind.NOTE, ComposeKind.GROUP, ComposeKind.CARD),
            pieces.map { it.kind }.toSet(),
        )
        assertTrue(answer.text.encodeToByteArray().size < CanvasComposeContract.MAX_RECEIPT_BYTES)
    }

    private fun assertThePiecesAreOnTheBoard(board: Board, receipt: ComposeReceipt) {
        val pieces = piecesOf(receipt)
        pieces.forEach { assertTrue("\"${it.boardId(receipt.artifactId)}\"" in board.sceneJson, it.key) }
        val composedDocuments = board.documents().filter { it.compose?.artifactId == receipt.artifactId }
        assertEquals(pieces.count { it.kind in DOCUMENT_KINDS }, composedDocuments.size)
        composedDocuments.forEach { document ->
            assertEquals(CanvasGeometryOwner.AUTO, document.owner, document.id)
            assertEquals(CanvasComposeContract.CATALOG, document.compose!!.catalog)
            assertEquals(CanvasComposeContract.VERSION, document.compose!!.version)
        }
    }

    /** The relay log applied by an app into its notebook, the app restarted, and the board read back. */
    private suspend fun reloadedThroughANotebook(): String {
        val path = Files.createTempDirectory("canvas-compose-e2e-")
        NotebookLocalStore(path, "e2e-peer").use { notebooks ->
            val session = CanvasSession.create(
                NotebookCanvasDocumentStore(notebooks),
                CanvasCreateOptions(canvasId = CANVAS_ID, conversationId = CONVERSATION, agentId = AGENT),
            )
            host.logged().forEach { entry -> assertNotNull(session.applyRemote(entry.op, vouchedActor = entry.op.actorId), "entry ${entry.cursor}") }
        }
        return NotebookLocalStore(path, "e2e-peer").use { notebooks ->
            assertNotNull(NotebookCanvasDocumentStore(notebooks).get(CANVAS_ID)).sceneJson
        }
    }

    private fun assertOneCardOnTheNarration(events: List<TimelineEvent>, receipt: ComposeReceipt, reloaded: Board) {
        val attached = CanvasArtifactReceipts.attach(events)
        val narrating = events.filterIsInstance<TimelineEvent.Confirmed>().single { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(setOf(CanvasArtifactReceipts.eventKey(narrating)), attached.keys)
        val card: CanvasArtifactReceipt = attached.values.single().single()
        assertEquals(CanvasArtifactStatus.Published, card.status)
        assertEquals(receipt.bounds, card.bounds)
        assertEquals(12, card.itemCount)
        assertEquals(CANVAS_ID.value, card.canvasId)
        assertTrue(card.canShowOnCanvas)
        card.pieceIds.forEach { assertTrue("\"$it\"" in reloaded.sceneJson, "the card names $it, which is not on the reloaded board") }
        // Hydrated from the stored envelope (a reload of the conversation): the same card.
        assertEquals(attached, CanvasArtifactReceipts.attach(hydrate(events)))
    }

    /** A second compose, named by its call, lands where placement puts it and over nothing. */
    private suspend fun assertASecondComposeLandsBeside(reloaded: Board, receipt: ComposeReceipt, existing: List<Rect>): Board {
        val occupied = CanvasComposePlacement.occupiedBounds(reloaded.sceneJson)
        val second = receiptOf(dispatch(ComposeCall("req-2", CALL_2, SECOND_REQUEST)))
        assertEquals(CanvasComposeIds.derived(CALL_2), second.artifactId, "no artifact_id: named by the call")
        val secondBounds = assertNotNull(second.bounds)
        val (originX, originY) = CanvasComposePlacement.origin(occupied)
        assertEquals(originX, secondBounds.x)
        assertEquals(originY, secondBounds.y)
        val afterSecond = Board(host.scene().sceneJson)
        assertLaidOutCleanly(afterSecond, second, existing + afterSecond.rects(receipt.artifactId).values)
        assertTrue(!secondBounds.intersects(receipt.bounds!!), "$secondBounds over $receipt")
        return afterSecond
    }

    /** A retry of [first] writes nothing and answers the same receipt; under another call id too, one card. */
    private suspend fun assertARetryIsIdempotent(first: ComposeCall, firstAnswered: Answered, board: Board) {
        val receipt = firstAnswered.receipt
        val logged = host.logged().size
        val retried = receiptOf(dispatch(first.copy(requestId = "req-3")))
        assertEquals(logged, host.logged().size, "a retry wrote to the board")
        assertEquals(board.sceneJson, host.scene().sceneJson)
        assertEquals(listOf(CanvasComposeService.ALREADY_PUBLISHED_WARNING), retried.warnings)
        assertEquals(receipt.copy(revision = retried.revision, warnings = retried.warnings), retried)
        // Under another call id too (the model sent it again): still the same artifact, one card.
        val again = first.copy(requestId = "req-4", toolCallId = CALL_3)
        val againAnswer = dispatch(again)
        assertEquals(receipt.bounds, receiptOf(againAnswer).bounds)
        val withRetry = timeline(first, firstAnswered.answer) + timeline(again, againAnswer, prefix = "retry")
        val cards = CanvasArtifactReceipts.attach(withRetry).values.flatten()
        assertEquals(1, cards.size, "a retry is one card, not two")
        assertEquals(receipt.bounds, cards.single().bounds)
    }

    // --- layout checks -------------------------------------------------------------------------

    /**
     * The pieces of [receipt] on [board]: none overlaps another (a group's frame holds its
     * children and its label, and nothing else), none overlaps [existing], and all lie inside the
     * receipt's bounds.
     */
    private fun assertLaidOutCleanly(board: Board, receipt: ComposeReceipt, existing: Collection<Rect>) {
        val rects = board.rects(receipt.artifactId)
        val bounds = assertNotNull(receipt.bounds)
        val outer = Rect(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height)
        val groups = groupMembers(receipt)
        rects.forEach { (id, rect) ->
            assertTrue(outer.contains(rect), "$id $rect is outside the receipt bounds $outer")
            existing.forEach { assertTrue(!rect.intersects(it), "$id $rect is over existing content $it") }
        }
        groups.forEach { (group, members) ->
            val frame = rects.getValue(group)
            members.forEach { assertTrue(frame.contains(rects.getValue(it)), "$it is outside its group $frame") }
        }
        assertNoOverlapsOutsideGroups(rects, groups)
        assertEquals(piecesOf(receipt).size + groups.size, rects.size, "pieces and group labels")
    }

    /** Each group's board id with the ids its frame holds: its children and its label. */
    private fun groupMembers(receipt: ComposeReceipt): Map<String, List<String>> =
        receipt.items.filter { it.kind == ComposeKind.GROUP }.associate { group ->
            group.boardId(receipt.artifactId) to (group.children.orEmpty().map { it.boardId(receipt.artifactId) } + CanvasComposeIds.label(receipt.artifactId, group.key))
        }

    /** No two of [rects] overlap, but a group's frame and what it holds. */
    private fun assertNoOverlapsOutsideGroups(rects: Map<String, Rect>, groups: Map<String, List<String>>) {
        fun nested(a: String, b: String) = groups[a]?.contains(b) == true || groups[b]?.contains(a) == true
        val ids = rects.keys.toList()
        ids.forEachIndexed { i, a ->
            ids.drop(i + 1).filterNot { b -> nested(a, b) }.forEach { b ->
                assertTrue(!rects.getValue(a).intersects(rects.getValue(b)), "$a ${rects[a]} overlaps $b ${rects[b]}")
            }
        }
    }

    /** The two boards hold the same documents (content, frame, owner, provenance, colour, title) and elements. */
    private fun assertSameBoard(expected: Board, actual: Board) {
        val want = expected.documents().associateBy { it.id }
        val got = actual.documents().associateBy { it.id }
        assertEquals(want.keys, got.keys)
        want.forEach { (id, document) ->
            val other = got.getValue(id)
            assertEquals(document.json, other.json, id)
            assertEquals(document.frame, other.frame, id)
            assertEquals(document.owner, other.owner, id)
            assertEquals(document.compose, other.compose, id)
            assertEquals(document.color, other.color, id)
            assertEquals(document.title, other.title, id)
        }
        assertEquals(expected.elementContent(), actual.elementContent())
    }

    // --- the chat side -------------------------------------------------------------------------

    /** The run as the App Server streams it: the user's ask, the call, its return, the narration. */
    private fun timeline(call: ComposeCall, answer: Answer, prefix: String = "run"): List<TimelineEvent> {
        val run = "$prefix-${call.toolCallId}"
        val frames: List<LettaMessage> = listOf(
            UserMessage(id = "$run-user", contentRaw = JsonPrimitive("Plan the Lisbon trip on the board"), runId = run, otid = "$run-otid-user"),
            ToolCallMessage(
                id = "$run-call", runId = run, seqId = 1, otid = "$run-otid-call",
                toolCall = ToolCall(id = call.toolCallId, name = CanvasToolContract.COMPOSE, arguments = call.input),
            ),
            toolReturnFrame(run, call, answer),
            AssistantMessage(id = "$run-assistant", contentRaw = JsonPrimitive("It's on the board."), runId = run, seqId = 3, otid = "$run-otid-a"),
        )
        var timeline = Timeline(conversationId = CONVERSATION)
        var pending = persistentMapOf<String, ToolReturnMessage>()
        frames.forEach { frame ->
            val out = reduceStreamFrame(TimelineReducerInput(prev = timeline, frame = frame, pendingToolReturnsByCallId = pending))
            timeline = out.next
            pending = out.updatedPendingToolReturnsByCallId
        }
        return timeline.events
    }

    private fun toolReturnFrame(run: String, call: ComposeCall, answer: Answer): LettaMessage {
        val status = if (answer.isError) "error" else "success"
        val frame = buildJsonObject {
            put("message_type", "tool_return_message")
            put("id", "$run-return")
            put("run_id", run)
            put("seq_id", 2)
            put("status", status)
            put("tool_call_id", call.toolCallId)
            put("tool_return", answer.text)
            putJsonArray("tool_returns") {
                add(buildJsonObject { put("tool_call_id", call.toolCallId); put("status", status); put("tool_return", answer.text) })
            }
        }
        return json.decodeFromJsonElement(LettaMessageSerializer, frame)
    }

    private fun hydrate(events: List<TimelineEvent>): List<TimelineEvent> {
        val envelope = StoredTimelineEnvelope(
            scope = TimelineScope("backend-1", CONVERSATION),
            revision = 1,
            events = events.filterIsInstance<TimelineEvent.Confirmed>().map { it.toStoredTimelineEvent() },
        )
        val text = TimelineSnapshotCodec.json.encodeToString(StoredTimelineEnvelope.serializer(), envelope)
        return TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEnvelope.serializer(), text).events.map(StoredTimelineEvent::toConfirmedTimelineEvent)
    }

    /** A board's scene, read the ways these checks need. */
    private class Board(val sceneJson: String) {
        fun documents(): List<CanvasSceneDocument> = CanvasOpProjector.documentsOf(sceneJson)

        private fun elements(): List<JsonObject> =
            (json.parseToJsonElement(sceneJson).jsonObject["elements"] as? JsonArray).orEmpty().map { it.jsonObject }

        /**
         * Rectangles by id: framed documents and elements, of the artifact [artifactId] (or, with
         * null, of nothing composed), with the placement engine's own element geometry.
         */
        fun rects(artifactId: String?): Map<String, Rect> {
            val documents = documents().filter { it.compose?.artifactId == artifactId }
                .associate { d -> d.id to d.frame!!.let { Rect(it.x, it.y, it.x + it.width, it.y + it.height) } }
            val elements = elements()
                .filter { ((it["_compose"] as? JsonObject)?.get("artifactId") as? JsonPrimitive)?.content == artifactId }
                .associate { element ->
                    val slot = CanvasComposePlacement.elementBounds(element, conservative = false)
                    element.getValue("id").jsonPrimitive.content to Rect(slot.x, slot.y, slot.right, slot.bottom)
                }
            return documents + elements
        }

        /** Each element's content by id: what a reload must keep. */
        fun elementContent(): Map<String, Map<String, JsonElement>> =
            elements().associate { it.getValue("id").jsonPrimitive.content to it.filterKeys { key -> key in ELEMENT_CONTENT } }
    }

    private data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun intersects(o: Rect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

        fun contains(o: Rect): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom
    }

    private fun ComposeBounds.intersects(o: ComposeBounds): Boolean =
        Rect(x, y, x + width, y + height).intersects(Rect(o.x, o.y, o.x + o.width, o.y + o.height))

    private companion object {
        const val CALL_1 = "toolu_01LisbonTripCompose"
        const val CALL_2 = "toolu_01RestaurantsCompose"
        const val CALL_3 = "toolu_01LisbonTripAgain"
        val CANVAS_ID = CanvasId.forConversation(CONVERSATION)
        val DOCUMENT_KINDS = setOf(ComposeKind.NOTE, ComposeKind.CHECKLIST, ComposeKind.CARD)
        val ELEMENT_CONTENT = setOf("type", "shapeType", "points", "text", "textTopLeft", "wrapWidth", "fontSize", "_compose")
        val json = Json { ignoreUnknownKeys = true }

        /** Every piece of [receipt]: its items and their children. */
        fun piecesOf(receipt: ComposeReceipt) = receipt.items.flatMap { listOf(it) + it.children.orEmpty() }

        /** A second artifact the agent adds later, named by its call. */
        val SECOND_REQUEST = """{"title":"Where to eat","items":[
            {"kind":"NOTE","title":"Alfama","markdown":"- Taberna Sal Grosso\n- Prado"},
            {"kind":"NOTE","title":"Baixa","markdown":"- Cervejaria Ramiro\n- *O Trevo* for a bifana"},
            {"kind":"CHECKLIST","title":"Book ahead","items":[{"text":"Ramiro, Friday"},{"text":"Prado, Saturday"}]}
        ]}"""
    }
}
