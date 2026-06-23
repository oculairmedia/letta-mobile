package com.letta.mobile.desktop.commands

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.chat.desktopChatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Desktop slash-command API. The server exposes per-agent slash commands at
 * `GET /v1/agents/{id}/slash-commands` (builtins like `/goal` plus any installed
 * skill's commands). Selecting one fills the composer with its command text so
 * the user can add args and send — the server interprets the slash prefix.
 *
 * Parsed via the JSON element API since `:desktop` has no serialization plugin.
 */
class DesktopSlashCommandApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient = createDesktopLettaHttpClient(),
) : AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    suspend fun listAgentSlashCommands(agentId: String): List<DesktopSlashCommand> {
        val response = httpClient.get("$baseUrl/v1/agents/$agentId/slash-commands") { applyAuth() }
        response.requireSuccess()
        val root = desktopChatJson.parseToJsonElement(response.bodyAsText())
        val array: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject -> root["commands"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val command = obj["command"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("/")
                ?: return@mapNotNull null
            DesktopSlashCommand(
                command = command,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: command,
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                skillName = obj["skill_name"]?.jsonPrimitive?.contentOrNull,
                installed = obj["installed"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    override fun close() {
        httpClient.close()
    }

    private fun HttpRequestBuilder.applyAuth() {
        config.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let(::bearerAuth)
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value !in 200..299) {
            throw IllegalStateException("Slash command API ${status.value}: ${bodyAsText()}")
        }
    }
}

data class DesktopSlashCommand(
    val command: String,
    val name: String,
    val description: String = "",
    val skillName: String? = null,
    val installed: Boolean = false,
)
