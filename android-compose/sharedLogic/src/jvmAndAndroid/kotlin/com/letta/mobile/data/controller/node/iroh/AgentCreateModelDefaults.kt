package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ModelCatalogNormalizer
import com.letta.mobile.data.runtime.DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Agent-create body defaults for known model handles (context window + max output).
 * Extracted from [AgentAdminHandlers] to keep that file under CodeScene complexity gates.
 */
internal fun JsonObject?.withDefaultContextWindow(): JsonObject {
    val body = this
    val known = ModelCatalogNormalizer.knownLimitsForHandle(firstModelHandle(body))
    return buildJsonObject {
        body?.forEach { (key, value) -> put(key, value) }
        if (!body.hasExplicitContextWindowLimit()) {
            put(
                "context_window_limit",
                known?.contextWindow ?: DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT,
            )
        }
        known?.let { limits ->
            body.withKnownMaxOutputTokens(limits.maxOutputTokens)?.let { put("model_settings", it) }
        }
    }
}

private fun JsonObject?.hasExplicitContextWindowLimit(): Boolean {
    if (this == null) return false
    if (hasAnyKey("context_window_limit", "contextWindowLimit")) return true
    if (nestedObject("model_settings", "modelSettings")
            .hasAnyKey("context_window_limit", "contextWindowLimit")
    ) {
        return true
    }
    return nestedObject("llm_config", "llmConfig").hasAnyKey(
        "context_window", "context_window_limit", "contextWindow", "contextWindowLimit",
    )
}

/** Returns updated `model_settings` when max_output is missing; null if already set. */
private fun JsonObject?.withKnownMaxOutputTokens(maxOutputTokens: Int): JsonObject? {
    val modelSettings = this?.nestedObject("model_settings", "modelSettings")
    if (hasExplicitMaxOutput(modelSettings)) return null
    return buildJsonObject {
        modelSettings?.forEach { (k, v) -> put(k, v) }
        put("max_output_tokens", maxOutputTokens)
    }
}

private fun JsonObject?.hasExplicitMaxOutput(modelSettings: JsonObject?): Boolean =
    modelSettings.hasAnyKey("max_output_tokens", "maxOutputTokens", "max_tokens", "maxTokens") ||
        this.hasAnyKey("max_output_tokens", "max_tokens", "maxTokens") ||
        this?.nestedObject("llm_config", "llmConfig")
            .hasAnyKey("max_tokens", "maxTokens", "max_output_tokens", "maxOutputTokens")

private fun JsonObject?.hasAnyKey(vararg keys: String): Boolean =
    this != null && keys.any { containsKey(it) }

private fun JsonObject.nestedObject(vararg keys: String): JsonObject? =
    keys.firstNotNullOfOrNull { key -> this[key] as? JsonObject }

private fun firstModelHandle(body: JsonObject?): String? {
    if (body == null) return null
    stringField(body, "model", "handle")?.let { return it }
    stringField(body.nestedObject("model_settings", "modelSettings"), "handle", "model")?.let { return it }
    return stringField(body.nestedObject("llm_config", "llmConfig"), "handle", "model")
}

private fun stringField(obj: JsonObject?, vararg keys: String): String? {
    if (obj == null) return null
    for (key in keys) {
        val value = (obj[key] as? JsonPrimitive)?.contentOrNull
        if (!value.isNullOrBlank()) return value
    }
    return null
}
