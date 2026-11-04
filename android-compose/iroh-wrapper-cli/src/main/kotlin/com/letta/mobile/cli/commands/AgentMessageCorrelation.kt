package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun parseCorrelation(entries: List<String>, json: String?): Map<String, String>? {
    if (json != null) {
        if (entries.isNotEmpty()) throw UsageError("Use only one of --correlation or --correlation-json")
        return parseCorrelationJson(json)
    }
    if (entries.isEmpty()) return null
    return entries.associate { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0) throw UsageError("--correlation requires key=value")
        entry.substring(0, separator) to entry.substring(separator + 1)
    }
}

private fun parseCorrelationJson(json: String): Map<String, String> = runCatching {
    val obj = Json.parseToJsonElement(json) as JsonObject
    obj.mapValues { (_, value) ->
        require(value is JsonPrimitive && value.isString)
        value.content
    }
}.getOrElse { throw UsageError("--correlation-json requires an object of strings") }
