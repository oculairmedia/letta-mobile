package com.letta.mobile.data.canvas

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-qygvv.21: a scene already in the log with elements DrawBox cannot read still draws
 * the rest, and says what it dropped.
 */
class CanvasSceneRenderGuardTest {
    private val json = Json

    private val invented = buildJsonObject {
        put("id", "rect-1")
        put("type", "rectangle")
        put("x", 100)
    }

    private val badPoint = JsonObject(
        CanvasSceneSchema.shape.defaults + CanvasSceneSchema.shape.example +
            mapOf("id" to JsonPrimitive("bad-1"), "points" to JsonArray(listOf(JsonPrimitive("a,b"), JsonPrimitive("1.0,2.0")))),
    )

    private val drawable = JsonObject(CanvasSceneSchema.text.defaults + CanvasSceneSchema.text.example)

    private fun scene(vararg elements: JsonObject) = buildJsonObject {
        put("bgColor", CanvasSceneSchema.DEFAULT_BG_COLOR)
        put("elements", JsonArray(elements.toList()))
    }.toString()

    private fun idsOf(sceneJson: String) =
        json.parseToJsonElement(sceneJson).jsonObject.getValue("elements").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun undrawableElementsAreDroppedAndTheRestKept() {
        val kept = CanvasSceneRenderGuard.renderable(scene(invented, badPoint, drawable), CANVAS)

        assertEquals(listOf("title-1"), idsOf(kept))
    }

    @Test
    fun eachDroppedElementIsReportedAsAWarning() {
        Telemetry.clear()

        CanvasSceneRenderGuard.renderable(scene(invented, drawable), CANVAS)

        val warning = Telemetry.snapshot().single { it.name == "scene.elementDropped" }
        assertEquals(Telemetry.Level.WARN, warning.level)
        assertEquals(CANVAS, warning.attrs["canvasId"])
        assertEquals("rect-1", warning.attrs["elementId"])
        assertEquals("rectangle", warning.attrs["type"])
    }

    @Test
    fun aDrawableSceneIsHandedOnUnchanged() {
        val original = scene(drawable)

        assertEquals(original, CanvasSceneRenderGuard.renderable(original, CANVAS))
    }

    private companion object {
        const val CANVAS = "canvas-conversation-conv-1"
    }
}
