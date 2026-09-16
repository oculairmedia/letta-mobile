package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Projects a sequence of [CanvasOp] operations onto a DrawBox scene JSON string.
 */
object CanvasOpProjector {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    private const val DEFAULT_BG_COLOR = "#ffffffff"

    /**
     * Projects [ops] sequentially onto [baseSceneJson].
     *
     * If [baseSceneJson] is blank or empty, projects starting from an empty canvas scene.
     */
    fun project(baseSceneJson: String, ops: List<CanvasOp>): String {
        var currentJson = baseSceneJson
        for (op in ops) {
            currentJson = projectSingle(currentJson, op)
        }
        return currentJson
    }

    private fun projectSingle(sceneJson: String, op: CanvasOp): String {
        return when (op) {
            is CanvasOp.ReplaceSceneOp -> {
                if (op.sceneJson.isNotBlank()) op.sceneJson else emptySceneJson()
            }
            is CanvasOp.SetBackgroundOp -> {
                val parsed = parseScene(sceneJson)
                val updated = buildJsonObject {
                    parsed.forEach { (key, value) ->
                        if (key != "bgColor") put(key, value)
                    }
                    put("bgColor", JsonPrimitive(op.colorHex))
                }
                json.encodeToString(JsonObject.serializer(), updated)
            }
            is CanvasOp.AddElementOp -> {
                val parsed = parseScene(sceneJson)
                val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
                val elementObj = parseElementWithId(op.elementId, op.elementJson)
                // Element-level LWW: if an element with the same ID already exists, update/replace it
                val existingIndex = elements.indexOfFirst {
                    runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() == op.elementId
                }
                if (existingIndex >= 0) {
                    elements[existingIndex] = elementObj
                } else {
                    elements.add(elementObj)
                }
                val updated = buildJsonObject {
                    parsed.forEach { (key, value) ->
                        if (key != "elements") put(key, value)
                    }
                    put("elements", JsonArray(elements))
                }
                json.encodeToString(JsonObject.serializer(), updated)
            }
            is CanvasOp.UpdateElementOp -> {
                val parsed = parseScene(sceneJson)
                val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
                val elementObj = parseElementWithId(op.elementId, op.elementJson)
                val existingIndex = elements.indexOfFirst {
                    runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() == op.elementId
                }
                if (existingIndex >= 0) {
                    elements[existingIndex] = elementObj
                } else {
                    elements.add(elementObj)
                }
                val updated = buildJsonObject {
                    parsed.forEach { (key, value) ->
                        if (key != "elements") put(key, value)
                    }
                    put("elements", JsonArray(elements))
                }
                json.encodeToString(JsonObject.serializer(), updated)
            }
            is CanvasOp.RemoveElementOp -> {
                val parsed = parseScene(sceneJson)
                val elements = parsed["elements"]?.jsonArray?.filterNot {
                    runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() == op.elementId
                } ?: emptyList()
                val updated = buildJsonObject {
                    parsed.forEach { (key, value) ->
                        if (key != "elements") put(key, value)
                    }
                    put("elements", JsonArray(elements))
                }
                json.encodeToString(JsonObject.serializer(), updated)
            }
            is CanvasOp.BatchOp -> {
                project(sceneJson, op.ops)
            }
        }
    }

    private fun parseScene(sceneJson: String): JsonObject {
        if (sceneJson.isBlank()) return parseEmptyScene()
        return try {
            json.parseToJsonElement(sceneJson).jsonObject
        } catch (_: Exception) {
            parseEmptyScene()
        }
    }

    private fun parseEmptyScene(): JsonObject {
        return buildJsonObject {
            put("bgColor", JsonPrimitive(DEFAULT_BG_COLOR))
            put("elements", JsonArray(emptyList()))
        }
    }

    fun emptySceneJson(): String {
        return json.encodeToString(JsonObject.serializer(), parseEmptyScene())
    }

    private fun parseElementWithId(elementId: String, elementJson: String): JsonObject {
        val parsed = try {
            json.parseToJsonElement(elementJson).jsonObject
        } catch (_: Exception) {
            buildJsonObject {
                put("id", JsonPrimitive(elementId))
            }
        }
        if (runCatching { parsed["id"]?.jsonPrimitive?.content }.getOrNull() == elementId) {
            return parsed
        }
        return buildJsonObject {
            put("id", JsonPrimitive(elementId))
            parsed.forEach { (key, value) ->
                if (key != "id") put(key, value)
            }
        }
    }
}
