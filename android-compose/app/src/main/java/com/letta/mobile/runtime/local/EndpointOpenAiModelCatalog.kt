package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LlmModel
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Lists models from a custom OpenAI-compatible endpoint (GET {base}/models)
 * so they appear in every model picker when a custom provider is configured
 * (letta-mobile-3icw7). Results are cached per endpoint+key; failures fall
 * back to the last good list so pickers degrade gracefully offline.
 */
@Singleton
open class EndpointOpenAiModelCatalog @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    // Last-good list per endpoint+key, so switching endpoints (or a fetch
    // failure for a previously used one) degrades to that endpoint's own
    // cached models rather than a single global slot (CodeRabbit).
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<LlmModel>>()

    open suspend fun listModels(baseUrl: String, apiKey: String?): List<LlmModel> = withContext(Dispatchers.IO) {
        val key = "$baseUrl|${apiKey.orEmpty()}"
        val fetched = runCatching { fetch(baseUrl, apiKey) }.getOrNull()
        if (fetched != null) {
            cache[key] = fetched
            fetched
        } else {
            cache[key].orEmpty()
        }
    }

    open suspend fun listServedModelIdsOrNull(baseUrl: String, apiKey: String?): List<String>? = withContext(Dispatchers.IO) {
        runCatching { fetch(baseUrl, apiKey).map { model -> model.handle ?: model.name.ifBlank { model.id } } }.getOrNull()
    }

    private fun fetch(baseUrl: String, apiKey: String?): List<LlmModel> {
        val connection = (URL("${baseUrl.trimEnd('/')}/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            requestMethod = "GET"
            apiKey?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            check(connection.responseCode in 200..299) { "models endpoint returned ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { entry ->
                val item = entry as? JsonObject ?: return@mapNotNull null
                val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@mapNotNull null
                LlmModel(
                    id = id,
                    name = id,
                    handle = id,
                    providerType = "local-lettacode",
                    providerName = "Custom endpoint",
                    contextWindow = (item["context_length"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    maxOutputTokens = (item["max_completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
