package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A model's canvas tool input read the way it is usually written (letta-mobile-qygvv.21): a scene or
 * element given as a JSON object rather than a string of one is taken as its string, and an op's
 * `opId`, `actorId` and `lamport` may be left out, since the host replaces all three anyway.
 */
internal object HostCanvasToolInputs {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The JSON-string fields of ops and tool inputs that a model may send as objects. */
    private val embeddedJson = setOf("sceneJson", "elementJson", "documentJson")

    fun string(input: JsonObject, key: String): String? = (input[key] as? JsonPrimitive)?.contentOrNull

    /** `scene_json` as a string, whether it came as one or as the object itself. */
    fun sceneJson(input: JsonObject): String? = when (val value = input["scene_json"]) {
        is JsonObject -> value.toString()
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

    /** `ops`, each completed ([completed]) and decoded. */
    fun ops(opsJson: JsonElement): List<CanvasOp> {
        val array = opsJson as? JsonArray ?: throw IllegalArgumentException("ops must be an array of op objects")
        return json.decodeFromJsonElement<List<CanvasOp>>(JsonArray(array.map(::completed)))
    }

    private fun completed(op: JsonElement): JsonElement {
        val obj = op as? JsonObject ?: return op
        val fields = obj.mapValues { (key, value) -> embedded(key, value) }.toMutableMap()
        IDENTITY_DEFAULTS.forEach { (key, value) -> if (key !in fields) fields[key] = value }
        (fields["ops"] as? JsonArray)?.let { nested -> fields["ops"] = JsonArray(nested.map(::completed)) }
        return JsonObject(fields)
    }

    private fun embedded(key: String, value: JsonElement): JsonElement =
        if (key in embeddedJson && value is JsonObject) JsonPrimitive(value.toString()) else value

    private val IDENTITY_DEFAULTS: Map<String, JsonElement> = mapOf(
        "opId" to JsonPrimitive(""),
        "actorId" to JsonPrimitive(""),
        "lamport" to JsonPrimitive(0),
    )
}
