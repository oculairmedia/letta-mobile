package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.CanvasBatchFixtures.addShape
import com.letta.mobile.data.canvas.CanvasBatchFixtures.labelledBox
import com.letta.mobile.data.canvas.CanvasBatchFixtures.remove
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

        val drawn = guard.drawable(withDuplicate(sceneOf(listOf(addShape("box-1"), addShape("box-2")))))

        assertEquals(listOf("box-2", "box-1"), idsOf(drawn))
        val warning = invalidStateWarnings().single()
        assertEquals(Telemetry.Level.WARN, warning.level)
        assertEquals(CanvasStateInvariant.ELEMENT_DUPLICATE_ID.wire, warning.attrs["invariant"])
        assertEquals(CANVAS, warning.attrs["canvasId"])
    }

    @Test
    fun aSyncThatBreaksTheBoardKeepsTheLastSceneThatDrew() {
        val guard = CanvasSceneStateGuard(CANVAS)
        val good = sceneOf(listOf(addShape("box-1")))
        val firstDrawn = guard.drawable(good)

        val afterBadSync = guard.drawable(withDuplicate(sceneOf(listOf(addShape("box-1"), addShape("box-2")))))

        assertEquals(firstDrawn, afterBadSync)
    }

    @Test
    fun aDanglingLabelIsReportedButTheBoardStillFollowsTheLog() {
        Telemetry.clear()
        val guard = CanvasSceneStateGuard(CANVAS)
        val dangling = sceneOf(labelledBox("box-1", "label-1") + addShape("box-2") + remove("box-1"))

        val drawn = guard.drawable(dangling)
        guard.drawable(dangling)

        assertEquals(listOf("box-2"), idsOf(drawn))
        assertEquals(listOf(CanvasStateInvariant.LABEL_OWNER.wire), invalidStateWarnings().map { it.attrs["invariant"] }, "reported once")
    }

    @Test
    fun aSoundSceneDrawsAsTheRenderGuardHasIt() {
        val scene = sceneOf(listOf(addShape("box-1")))

        val drawn = CanvasSceneStateGuard(CANVAS).drawable(scene)

        assertEquals(CanvasSceneRenderGuard.renderable(CanvasOpProjector.stripMetadataForDrawBox(scene), CANVAS), drawn)
    }

    private companion object {
        const val CANVAS = "canvas-conversation-conv-guard"
    }
}
