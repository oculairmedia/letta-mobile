package com.letta.mobile.cli.commands

import com.letta.mobile.cli.runtime.CliJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun jsonStatus(name: String, value: String): String =
    CliJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf(name to JsonPrimitive(value))))
