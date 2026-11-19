package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * [value] as a host contract's wire JSON: every default written (`has_api_key: false`, `config: {}`)
 * and every null omitted, as the voice and service-credential contracts' writers do.
 */
internal fun <T> hostWireJson(serializer: KSerializer<T>, value: T): JsonElement =
    LettaJson.persisted.encodeToJsonElement(serializer, value).withoutNulls()

private fun JsonElement.withoutNulls(): JsonElement = when (this) {
    is JsonObject -> JsonObject(filterValues { it !is JsonNull }.mapValues { it.value.withoutNulls() })
    is JsonArray -> JsonArray(map { it.withoutNulls() })
    else -> this
}
