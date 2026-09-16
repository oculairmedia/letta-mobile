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
     * @param opIdGenerator Function providing unique operation IDs
     */
    fun diff(
        oldSceneJson: String,
        newSceneJson: String,
        actorId: String,
        lamportSupplier: () -> Long,
        opIdGenerator: () -> String = { generateOpId() }
    ): List<CanvasOp> {
        if (oldSceneJson == newSceneJson) return emptyList()

        val oldParsed = cleanScene(parseScene(oldSceneJson))
        val newParsed = cleanScene(parseScene(newSceneJson))
        if (oldParsed == newParsed) return emptyList()

        val ops = mutableListOf<CanvasOp>()

        // 1. Background color diff
        val oldBg = runCatching { oldParsed["bgColor"]?.jsonPrimitive?.content }.getOrNull()
        val newBg = runCatching { newParsed["bgColor"]?.jsonPrimitive?.content }.getOrNull()
        if (newBg != null && newBg != oldBg) {
            ops.add(
                CanvasOp.SetBackgroundOp(
                    opId = opIdGenerator(),
                    actorId = actorId,
                    lamport = lamportSupplier(),
                    colorHex = newBg,
                )
            )
        }

        // 2. Elements diff
        val oldElements = extractElementsMap(oldParsed)
        val newElements = extractElementsMap(newParsed)

        // Removed elements (in old but not in new)
        for ((oldId, _) in oldElements) {
            if (!newElements.containsKey(oldId)) {
                ops.add(
                    CanvasOp.RemoveElementOp(
                        opId = opIdGenerator(),
                        actorId = actorId,
                        lamport = lamportSupplier(),
                        elementId = oldId,
                    )
                )
            }
        }

        // Added elements (in new but not in old)
        for ((newId, newElemObj) in newElements) {
            if (!oldElements.containsKey(newId)) {
                ops.add(
                    CanvasOp.AddElementOp(
                        opId = opIdGenerator(),
                        actorId = actorId,
                        lamport = lamportSupplier(),
                        elementId = newId,
                        elementJson = json.encodeToString(JsonObject.serializer(), newElemObj),
                    )
                )
            } else {
                // Updated elements (in both but content differs)
                val oldElemObj = oldElements[newId]!!
                if (oldElemObj != newElemObj) {
                    ops.add(
                        CanvasOp.UpdateElementOp(
                            opId = opIdGenerator(),
                            actorId = actorId,
                            lamport = lamportSupplier(),
                            elementId = newId,
                            elementJson = json.encodeToString(JsonObject.serializer(), newElemObj),
                        )
                    )
                }
            }
        }

        return ops
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
