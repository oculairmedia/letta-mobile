package com.letta.mobile.data.canvas

import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Computes the delta [CanvasOp] operations between two DrawBox scene JSON strings.
 */
object CanvasOpDiffer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun generateOpId(prefix: String = "op"): String {
        val high = (Random.nextLong() and Long.MAX_VALUE).toString(16)
        val low = Random.nextInt(0, 0xffff).toString(16)
        return "$prefix-$high-$low"
    }

    fun canonicalize(sceneJson: String): String {
        val parsed = cleanScene(parseScene(sceneJson))
        return json.encodeToString(JsonObject.serializer(), parsed)
    }

    /**
     * Strips internal synchronization metadata (e.g. _lamport, _actorId) from a scene,
     * returning a clean semantic JsonObject suitable for content equality comparisons.
     */
    fun cleanScene(scene: JsonObject): JsonObject {
        val elementsArray = runCatching { scene["elements"]?.jsonArray }.getOrNull()
        val cleanedElements = elementsArray?.map { elem ->
            val obj = runCatching { elem.jsonObject }.getOrNull()
            if (obj != null) cleanElement(obj) else elem
        }
        return buildJsonObject {
            scene.forEach { (key, value) ->
                if (!key.startsWith("_") && key != "elements") {
                    put(key, value)
                }
            }
            if (cleanedElements != null) {
                put("elements", kotlinx.serialization.json.JsonArray(cleanedElements))
            }
        }
    }

    /**
     * Strips internal synchronization metadata (e.g. _lamport, _actorId) from an element.
     */
    fun cleanElement(elem: JsonObject): JsonObject {
        val hasMetadata = elem.keys.any { it.startsWith("_") }
        if (!hasMetadata) return elem
        return buildJsonObject {
            elem.forEach { (k, v) ->
                if (!k.startsWith("_")) put(k, v)
            }
        }
    }

    private data class DiffTarget(
        val actorId: String,
        val lamportSupplier: () -> Long,
    )

    /**
     * Diffs [oldSceneJson] against [newSceneJson] and returns a list of discrete operations.
     *
     * Ignores synchronization metadata (_lamport, _actorId) so that clean DrawBox exports
     * compared against metadata-tagged session scenes produce zero phantom operations (N1).
     *
     * @param oldSceneJson Previous snapshot of the canvas scene
     * @param newSceneJson Newly exported canvas scene
     * @param actorId Author identifier for the generated operations
     * @param lamportSupplier Function providing incrementing Lamport timestamps
     */
    fun diff(
        oldSceneJson: String,
        newSceneJson: String,
        actorId: String,
        lamportSupplier: () -> Long,
    ): List<CanvasOp> {
        if (oldSceneJson == newSceneJson) return emptyList()

        val oldParsed = cleanScene(parseScene(oldSceneJson))
        val newParsed = cleanScene(parseScene(newSceneJson))
        if (oldParsed == newParsed) return emptyList()

        val ops = mutableListOf<CanvasOp>()
        val target = DiffTarget(actorId, lamportSupplier)
        diffBackground(oldParsed, newParsed, target)?.let { ops.add(it) }
        diffElements(oldParsed, newParsed, target, ops)
        return ops
    }

    private fun diffBackground(
        oldParsed: JsonObject,
        newParsed: JsonObject,
        target: DiffTarget,
    ): CanvasOp.SetBackgroundOp? {
        val oldBg = runCatching { oldParsed["bgColor"]?.jsonPrimitive?.content }.getOrNull()
        val newBg = runCatching { newParsed["bgColor"]?.jsonPrimitive?.content }.getOrNull()
        if (newBg == null || newBg == oldBg) return null
        return CanvasOp.SetBackgroundOp(
            opId = generateOpId(),
            actorId = target.actorId,
            lamport = target.lamportSupplier(),
            colorHex = newBg,
        )
    }

    private fun diffElements(
        oldParsed: JsonObject,
        newParsed: JsonObject,
        target: DiffTarget,
        destination: MutableList<CanvasOp>,
    ) {
        val oldElements = extractElementsMap(oldParsed)
        val newElements = extractElementsMap(newParsed)
        findRemovedElements(oldElements, newElements, target, destination)
        findAddedOrUpdatedElements(oldElements, newElements, target, destination)
    }

    private fun findRemovedElements(
        oldElements: Map<String, JsonObject>,
        newElements: Map<String, JsonObject>,
        target: DiffTarget,
        destination: MutableList<CanvasOp>,
    ) {
        for ((oldId, _) in oldElements) {
            if (!newElements.containsKey(oldId)) {
                destination.add(
                    CanvasOp.RemoveElementOp(
                        opId = generateOpId(),
                        actorId = target.actorId,
                        lamport = target.lamportSupplier(),
                        elementId = oldId,
                    )
                )
            }
        }
    }

    private fun findAddedOrUpdatedElements(
        oldElements: Map<String, JsonObject>,
        newElements: Map<String, JsonObject>,
        target: DiffTarget,
        destination: MutableList<CanvasOp>,
    ) {
        for ((newId, newElemObj) in newElements) {
            val oldElemObj = oldElements[newId]
            if (oldElemObj == null) {
                destination.add(
                    CanvasOp.AddElementOp(
                        opId = generateOpId(),
                        actorId = target.actorId,
                        lamport = target.lamportSupplier(),
                        elementId = newId,
                        elementJson = json.encodeToString(JsonObject.serializer(), newElemObj),
                    )
                )
            } else if (oldElemObj != newElemObj) {
                destination.add(
                    CanvasOp.UpdateElementOp(
                        opId = generateOpId(),
                        actorId = target.actorId,
                        lamport = target.lamportSupplier(),
                        elementId = newId,
                        elementJson = json.encodeToString(JsonObject.serializer(), newElemObj),
                    )
                )
            }
        }
    }

    private fun parseScene(sceneJson: String): JsonObject {
        if (sceneJson.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(sceneJson).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    private fun extractElementsMap(scene: JsonObject): Map<String, JsonObject> {
        val elementsArray = runCatching { scene["elements"]?.jsonArray }.getOrNull() ?: return emptyMap()
        val map = linkedMapOf<String, JsonObject>()
        elementsArray.forEachIndexed { index, elem ->
            val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@forEachIndexed
            val id = runCatching { obj["id"]?.jsonPrimitive?.content }.getOrNull() ?: "elem-$index"
            map[id] = cleanElement(obj)
        }
        return map
    }
}
