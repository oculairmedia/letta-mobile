package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.GeneratedUiPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

fun extractGeneratedUi(raw: kotlinx.serialization.json.JsonElement?): GeneratedUiPayload? =
    runCatching {
        val obj = raw as? JsonObject ?: return@runCatching null
        val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        if (type != "generated_ui") return@runCatching null
        val component = (obj["component"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return@runCatching null
        GeneratedUiPayload(
            component = component,
            propsJson = obj["props"]?.toString() ?: buildJsonObject {}.toString(),
            fallbackText = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: (obj["fallback_text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
        )
    }.getOrNull()

fun extractGeneratedUiFromString(raw: String): GeneratedUiPayload? {
    if (raw.isBlank()) return null
    return runCatching { extractGeneratedUi(Json.parseToJsonElement(raw)) }.getOrNull()
}
