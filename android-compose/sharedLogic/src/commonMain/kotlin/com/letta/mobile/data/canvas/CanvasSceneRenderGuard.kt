package com.letta.mobile.data.canvas

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an app hands DrawBox's decoder: a scene with every element the decoder cannot read taken out
 * and reported (letta-mobile-qygvv.21).
 *
 * The decoder is all-or-nothing: one element missing `zIndex`, or with a point it cannot parse,
 * fails the whole scene and the board opens empty with nothing said. Already-logged scenes can hold
 * such elements (written before the host validated agent writes), so each is dropped here with a
 * WARN naming the canvas, the element and why, and the rest of the board still draws.
 */
object CanvasSceneRenderGuard {
    private val json = Json { prettyPrint = false }

    /** [sceneJson] with only the elements DrawBox can draw; unchanged when all of them can. */
    fun renderable(sceneJson: String, canvasId: String?): String {
        if (sceneJson.isBlank()) return sceneJson
        val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject ?: return sceneJson
        val elements = root["elements"] as? JsonArray ?: return sceneJson
        val kept = elements.filter { element -> fault(element)?.also { report(canvasId, element, it) } == null }
        val hasBackground = root["bgColor"]?.let { CanvasSceneFieldChecks.accepts(CanvasFieldKind.COLOR, it, strict = false) } == true
        if (kept.size == elements.size && hasBackground) return sceneJson
        val background = root["bgColor"].takeIf { hasBackground } ?: JsonPrimitive(CanvasSceneSchema.DEFAULT_BG_COLOR)
        val cleaned = JsonObject(root + mapOf("bgColor" to background, "elements" to JsonArray(kept)))
        return json.encodeToString(JsonObject.serializer(), cleaned)
    }

    private fun fault(element: JsonElement): String? =
        (element as? JsonObject)?.let(CanvasSceneElementFaults::undecodable) ?: "is not a JSON object".takeIf { element !is JsonObject }

    private fun report(canvasId: String?, element: JsonElement, reason: String) {
        val obj = element as? JsonObject
        Telemetry.event(
            "Canvas", "scene.elementDropped",
            "canvasId" to canvasId,
            "elementId" to obj?.stringOf("id"),
            "type" to obj?.stringOf("type"),
            "reason" to reason,
            level = Telemetry.Level.WARN,
        )
    }

    private fun JsonObject.stringOf(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
