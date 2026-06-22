package com.letta.mobile.desktop.schedules

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.chat.desktopChatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Desktop cron/schedule API. This Letta server exposes scheduled work at
 * `/v1/crons` (the REST `/v1/agents/{id}/schedule` path 404s here), so the
 * desktop Schedules surface reads cron tasks directly. The `:desktop` module
 * has no kotlinx-serialization compiler plugin, so the response is parsed via
 * the JSON element API rather than a generated serializer.
 */
class DesktopCronApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient = createDesktopLettaHttpClient(),
) : AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    suspend fun listCrons(): List<DesktopCronTask> {
        val response = httpClient.get("$baseUrl/v1/crons") { applyAuth() }
        response.requireSuccess()
        val root = desktopChatJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val tasks = root["tasks"]?.jsonArray ?: return emptyList()
        return tasks.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            DesktopCronTask(
                id = id,
                agentId = obj["agent_id"]?.jsonPrimitive?.contentOrNull,
                conversationId = obj["conversation_id"]?.jsonPrimitive?.contentOrNull,
                name = obj["name"]?.jsonPrimitive?.contentOrNull,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                cron = obj["cron"]?.jsonPrimitive?.contentOrNull,
                timezone = obj["timezone"]?.jsonPrimitive?.contentOrNull,
                recurring = obj["recurring"]?.jsonPrimitive?.booleanOrNull ?: false,
                prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    suspend fun deleteCron(id: String) {
        val response = httpClient.delete("$baseUrl/v1/crons/$id") { applyAuth() }
        response.requireSuccess()
    }

    override fun close() {
        httpClient.close()
    }

    private fun HttpRequestBuilder.applyAuth() {
        config.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let(::bearerAuth)
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value !in 200..299) {
            throw IllegalStateException("Cron API ${status.value}: ${bodyAsText()}")
        }
    }
}

data class DesktopCronTask(
    val id: String,
    val agentId: String? = null,
    val conversationId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val recurring: Boolean = false,
    val prompt: String? = null,
)
