package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGetSceneResult
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasSnap
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The re-aim an open board runs when a note moves (letta-mobile-i9yps.1): arrows a connect drew are
 * followed by [CanvasWorkspaceSupport.followConnectors], the production path behind
 * followNoteConnectors on Android and Desktop, not by a re-computation in the test.
 */
class CanvasConnectFollowTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relay = InMemoryCanvasRelayStore()
    private val registry = ExternalToolRegistry.hostTools(
        HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(relay, hostId = { "host-1" }), relay, InMemoryHostCanvasDirectory())),
    )

    @Test
    fun movingABoundNoteReaimsOnlyItsArrowsToTheSameSides() {
        val scene = runBlocking {
            publish(FRAMES.map { (id, frame) -> note(id, frame) } + LINKS.map { (id, link) -> connect(id, link) })
            sceneJson()
        }
        val controller = DrawBoxController(Reducer(UseCase()))
        DrawingSerializer.deserialize(CanvasOpProjector.stripMetadataForDrawBox(scene)).elements.forEach { controller.onIntent(Intent.AddElement(it)) }
        val documents = CanvasOpProjector.documentsOf(scene)
        val bindings = CanvasOpProjector.arrowBindingsOf(scene)
        val seen = CanvasWorkspaceSupport.followConnectors(params(controller, bindings, documents, emptyMap()))
        val unmoved = arrows(controller)

        val moved = FRAMES + ("n-b" to FRAMES.getValue("n-b").copy(y = 60f))
        val after = documents.map { doc -> doc.copy(frame = moved.getValue(doc.id)) }
        CanvasWorkspaceSupport.followConnectors(params(controller, bindings, after, seen))

        val arrows = arrows(controller)
        LINKS.forEach { (id, link) ->
            assertOnSide(arrows.getValue(id).points.first(), moved.getValue(link.from), link.fromSide, "$id start")
            assertOnSide(arrows.getValue(id).points.last(), moved.getValue(link.to), link.toSide, "$id end")
        }
        assertEquals(unmoved.getValue("a-ad").points, arrows.getValue("a-ad").points, "an arrow away from the moved note stays put")
    }

    private fun params(
        controller: DrawBoxController,
        bindings: Map<String, com.letta.mobile.data.canvas.CanvasArrowBinding>,
        documents: List<com.letta.mobile.data.canvas.CanvasSceneDocument>,
        lastFrames: Map<String, CanvasDocumentFrame>,
    ) = FollowConnectorsParams(controller, controller.state.value.elements, bindings, documents, lastFrames)

    private fun arrows(controller: DrawBoxController): Map<String, Element.Shape> =
        controller.state.value.elements.filterIsInstance<Element.Shape>().associateBy { it.id }

    private fun assertOnSide(point: Offset, frame: CanvasDocumentFrame, side: String, what: String) {
        val (x, y) = CanvasSnap.anchorOn(frame, side)
        assertEquals(x, point.x, 2f, "$what x")
        assertEquals(y, point.y, 2f, "$what y")
    }

    private suspend fun sceneJson(): String {
        val result = registry.invoke(CanvasToolContract.GET_SCENE, JsonObject(emptyMap()), CALLER)
        return json.decodeFromString(CanvasGetSceneResult.serializer(), assertIs<ExternalToolResult.Success>(result).content).sceneJson
    }

    private suspend fun publish(ops: List<JsonObject>) {
        val result = registry.invoke(CanvasToolContract.APPLY_OPS, buildJsonObject { put("ops", JsonArray(ops)) }, CALLER)
        assertIs<ExternalToolResult.Success>(result, "$result")
    }

    private fun note(id: String, frame: CanvasDocumentFrame): JsonObject = json.parseToJsonElement(
        """{"type":"set_document","documentId":"$id","documentJson":{"version":2,"blocks":[]},""" +
            """"frame":{"x":${frame.x},"y":${frame.y},"width":${frame.width},"height":${frame.height}}}""",
    ) as JsonObject

    private fun connect(id: String, link: Link): JsonObject = buildJsonObject {
        put("type", "connect")
        put("id", id)
        put("from", link.from)
        put("to", link.to)
    }

    private data class Link(val from: String, val fromSide: String, val to: String, val toSide: String)

    private companion object {
        const val CONVERSATION = "conv-connect-follow"
        val CALLER = ExternalToolCaller(agentId = "agent-1", conversationId = CONVERSATION)
        val FRAMES = mapOf(
            "n-a" to CanvasDocumentFrame(16f, 24f, 140f, 80f),
            "n-b" to CanvasDocumentFrame(240f, 24f, 140f, 80f),
            "n-c" to CanvasDocumentFrame(240f, 220f, 140f, 80f),
            "n-d" to CanvasDocumentFrame(16f, 220f, 140f, 80f),
        )
        val LINKS = mapOf(
            "a-ab" to Link("n-a", "right", "n-b", "left"),
            "a-bc" to Link("n-b", "bottom", "n-c", "top"),
            "a-ad" to Link("n-a", "bottom", "n-d", "top"),
        )
    }
}
