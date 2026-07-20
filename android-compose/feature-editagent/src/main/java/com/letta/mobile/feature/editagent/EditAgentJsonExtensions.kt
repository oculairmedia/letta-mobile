package com.letta.mobile.feature.editagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val editAgentPrettyJson = Json { prettyPrint = true }

internal fun JsonElement.toSettingsJson(): String {
    return editAgentPrettyJson.encodeToString(JsonElement.serializer(), this)
}
