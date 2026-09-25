package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A projected scene read once for the state checks: its elements (in order, duplicates kept), its
 * live documents and the label-owner and connector-binding tables the projector keeps beside
 * them. [of] is null when the scene is not a JSON object with an `elements` array.
 */
internal class CanvasSceneIndex private constructor(
    /** Each element, or null where the array holds something that is not an object. */
    val elements: List<JsonObject?>,
    val documents: List<CanvasSceneDocument>,
    val labelOwners: Map<String, String>,
    val arrowBindings: Map<String, CanvasArrowBinding>,
) {
    val elementIds: Set<String> = elements.mapNotNull { it?.idOrNull() }.toSet()
    val documentIds: Set<String> = documents.map { it.id }.toSet()

    fun hasElement(id: String): Boolean = id in elementIds

    fun hasDocument(id: String): Boolean = id in documentIds

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(sceneJson: String): CanvasSceneIndex? {
            val source = sceneJson.ifBlank { CanvasOpProjector.emptySceneJson() }
            val root = runCatching { json.parseToJsonElement(source) }.getOrNull() as? JsonObject ?: return null
            val elements = root["elements"] as? JsonArray ?: return null
            return CanvasSceneIndex(
                elements = elements.map { it as? JsonObject },
                documents = CanvasOpProjector.documentsOf(source),
                labelOwners = CanvasOpProjector.labelOwnersOf(source),
                arrowBindings = CanvasOpProjector.arrowBindingsOf(source),
            )
        }

        fun JsonObject.idOrNull(): String? = (this["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
