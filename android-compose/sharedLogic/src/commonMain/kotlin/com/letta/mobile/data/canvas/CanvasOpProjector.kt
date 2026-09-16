package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Projects a sequence of [CanvasOp] operations onto a DrawBox scene JSON string
 * with element-level Last-Write-Wins (LWW) conflict resolution.
 */
object CanvasOpProjector {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
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
                upsertElementWithLww(sceneJson, op, op.elementId, op.elementJson)
            }
            is CanvasOp.UpdateElementOp -> {
                upsertElementWithLww(sceneJson, op, op.elementId, op.elementJson)
            }
            is CanvasOp.RemoveElementOp -> {
                removeElementWithLww(sceneJson, op)
            }
            is CanvasOp.BatchOp -> {
                project(sceneJson, op.ops)
            }
        }
    }

    private fun upsertElementWithLww(
        sceneJson: String,
        op: CanvasOp,
        elementId: String,
        elementJson: String,
    ): String {
        val parsed = parseScene(sceneJson)
        val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIndex = elements.indexOfFirst {
            runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() == elementId
        }

        if (existingIndex >= 0) {
            val existingObj = elements[existingIndex].jsonObject
            val existingLamport = runCatching { existingObj["_lamport"]?.jsonPrimitive?.long }.getOrNull()
            val existingActor = runCatching { existingObj["_actorId"]?.jsonPrimitive?.content }.getOrNull().orEmpty()

            if (existingLamport != null) {
                val incomingWins = op.lamport > existingLamport ||
                    (op.lamport == existingLamport && op.actorId >= existingActor)
                if (!incomingWins) {
                    // Out-of-order older op; discard under LWW policy
                    return sceneJson
                }
            }
        }

        val newElement = parseElementWithMetadata(elementId, elementJson, op.lamport, op.actorId)
        if (existingIndex >= 0) {
            elements[existingIndex] = newElement
        } else {
            elements.add(newElement)
        }

        val updated = buildJsonObject {
            parsed.forEach { (key, value) ->
                if (key != "elements") put(key, value)
            }
            put("elements", JsonArray(elements))
        }
        return json.encodeToString(JsonObject.serializer(), updated)
    }

    private fun removeElementWithLww(
        sceneJson: String,
        op: CanvasOp.RemoveElementOp,
    ): String {
        val parsed = parseScene(sceneJson)
        val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIndex = elements.indexOfFirst {
            runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() == op.elementId
        }
        if (existingIndex < 0) return sceneJson

        val existingObj = elements[existingIndex].jsonObject
        val existingLamport = runCatching { existingObj["_lamport"]?.jsonPrimitive?.long }.getOrNull()
        val existingActor = runCatching { existingObj["_actorId"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        if (existingLamport != null) {
            val removeWins = op.lamport > existingLamport ||
                (op.lamport == existingLamport && op.actorId >= existingActor)
            if (!removeWins) {
                // Remove op is older than existing update; discard
                return sceneJson
            }
        }

        elements.removeAt(existingIndex)
        val updated = buildJsonObject {
            parsed.forEach { (key, value) ->
                if (key != "elements") put(key, value)
            }
            put("elements", JsonArray(elements))
        }
        return json.encodeToString(JsonObject.serializer(), updated)
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

    private fun parseElementWithMetadata(
        elementId: String,
        elementJson: String,
        lamport: Long,
        actorId: String,
    ): JsonObject {
        val parsed = try {
            json.parseToJsonElement(elementJson).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        return buildJsonObject {
            put("id", JsonPrimitive(elementId))
            parsed.forEach { (key, value) ->
                if (key != "id" && key != "_lamport" && key != "_actorId") put(key, value)
            }
            put("_lamport", JsonPrimitive(lamport))
            put("_actorId", JsonPrimitive(actorId))
        }
    }

    /**
     * Strips internal synchronization metadata (e.g. _lamport, _actorId) from elements,
     * producing a pure DrawBox scene JSON compatible with DrawBox's strict deserializer.
     */
    fun stripMetadataForDrawBox(sceneJson: String): String {
        if (sceneJson.isBlank()) return emptySceneJson()
        return try {
            val parsed = json.parseToJsonElement(sceneJson).jsonObject
            val elements = parsed["elements"]?.jsonArray?.map { elem ->
                val elemObj = elem.jsonObject
                buildJsonObject {
                    elemObj.forEach { (k, v) ->
                        if (!k.startsWith("_")) put(k, v)
                    }
                }
            } ?: emptyList()
            val cleaned = buildJsonObject {
                parsed.forEach { (k, v) ->
                    if (k != "elements" && !k.startsWith("_")) put(k, v)
                }
                put("elements", JsonArray(elements))
            }
            json.encodeToString(JsonObject.serializer(), cleaned)
        } catch (_: Exception) {
            sceneJson
        }
    }
}
