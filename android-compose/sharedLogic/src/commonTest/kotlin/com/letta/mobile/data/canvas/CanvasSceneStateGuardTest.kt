package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.CanvasBatchFixtures.box1
import com.letta.mobile.data.canvas.CanvasBatchFixtures.box2
import com.letta.mobile.data.canvas.CanvasBatchFixtures.label1
import com.letta.mobile.data.canvas.CanvasBatchFixtures.labelledBox
import com.letta.mobile.data.canvas.CanvasBatchFixtures.sceneOf
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-qygvv.30: a stored scene that breaks the board's rules still draws, and says so. */
class CanvasSceneStateGuardTest {
    private val json = Json

    private fun idsOf(sceneJson: String) =
        json.parseToJsonElement(sceneJson).jsonObject.getValue("elements").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    /** [sceneJson] with its first element written into it twice: what no projector makes, but a raw replace can. */
    private fun withDuplicate(sceneJson: String): String {
        val root = json.parseToJsonElement(sceneJson).jsonObject
        val elements = root.getValue("elements").jsonArray
        return JsonObject(root + ("elements" to JsonArray(elements + elements.first()))).toString()
    }

    private fun invalidStateWarnings() = Telemetry.snapshot().filter { it.name == "scene.invalidState" }

    @Test
    fun aSceneWithDuplicateIdsOpensDedupedAndIsReported() {
        Telemetry.clear()
        val guard = CanvasSceneStateGuard(CANVAS)

        val drawn = guard.drawable(withDuplicate(sceneOf(listOf(box1.add(), box2.add()))))

        assertEquals(listOf(box2.id, box1.id), idsOf(drawn))
        val warning = invalidStateWarnings().single()
        assertEquals(Telemetry.Level.WARN, warning.level)
        assertEquals(CanvasStateInvariant.ELEMENT_DUPLICATE_ID.wire, warning.attrs["invariant"])
        assertEquals(CANVAS, warning.attrs["canvasId"])
    }

    @Test
    fun aSyncThatBreaksTheBoardKeepsTheLastSceneThatDrew() {
        val guard = CanvasSceneStateGuard(CANVAS)
        val good = sceneOf(listOf(box1.add()))
        val firstDrawn = guard.drawable(good)

        val afterBadSync = guard.drawable(withDuplicate(sceneOf(listOf(box1.add(), box2.add()))))

        assertEquals(firstDrawn, afterBadSync)
    }

    @Test
    fun aDanglingLabelIsReportedButTheBoardStillFollowsTheLog() {
        Telemetry.clear()
        val guard = CanvasSceneStateGuard(CANVAS)
        val dangling = sceneOf(labelledBox(box1, label1) + box2.add() + box1.remove())

        val drawn = guard.drawable(dangling)
        guard.drawable(dangling)

        assertEquals(listOf(box2.id), idsOf(drawn))
        assertEquals(listOf(CanvasStateInvariant.LABEL_OWNER.wire), invalidStateWarnings().map { it.attrs["invariant"] }, "reported once")
    }

    @Test
    fun aSoundSceneDrawsAsTheRenderGuardHasIt() {
        val scene = sceneOf(listOf(box1.add()))

        val drawn = CanvasSceneStateGuard(CANVAS).drawable(scene)

        assertEquals(CanvasSceneRenderGuard.renderable(CanvasOpProjector.stripMetadataForDrawBox(scene), CANVAS), drawn)
    }

    private companion object {
        const val CANVAS = "canvas-conversation-conv-guard"
    }
}
