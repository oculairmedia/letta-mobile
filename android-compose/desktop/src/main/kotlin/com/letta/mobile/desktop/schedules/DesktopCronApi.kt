package com.letta.mobile.desktop.schedules

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Desktop cron/schedule API. This Letta server exposes scheduled work at
 * `/v1/crons` (the REST `/v1/agents/{id}/schedule` path 404s here), so the
 * desktop Schedules surface reads cron tasks directly. Deserialized with the
 * content-negotiation [body] helper.
 */
class DesktopCronApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient = createDesktopLettaHttpClient(),
) : AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    suspend fun listCrons(): List<DesktopCronTask> {
        val response = httpClient.get("$baseUrl/v1/crons") { applyAuth() }
        response.requireSuccess()
        return response.body<CronsResponse>().tasks
    }

    suspend fun deleteCron(id: String) {
        val response = httpClient.delete("$baseUrl/v1/crons/$id") { applyAuth() }
        response.requireSuccess()
    }

    /** Create a recurring cron task for [agentId]. */
    suspend fun createCron(
        agentId: String,
        name: String,
        description: String,
        prompt: String,
        cron: String,
        timezone: String,
        recurring: Boolean = true,
    ) {
        val response = httpClient.post("$baseUrl/v1/crons") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(
                CreateCronRequest(
                    agentId = agentId,
                    name = name,
                    description = description,
                    prompt = prompt,
                    cron = cron,
                    timezone = timezone,
                    recurring = recurring,
                ),
            )
        }
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

@Serializable
data class DesktopCronTask(
    val id: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val recurring: Boolean = false,
    val prompt: String? = null,
)

@Serializable
private data class CronsResponse(
    val tasks: List<DesktopCronTask> = emptyList(),
)

@Serializable
private data class CreateCronRequest(
    @SerialName("agent_id") val agentId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val cron: String,
    val timezone: String,
    val recurring: Boolean,
)
