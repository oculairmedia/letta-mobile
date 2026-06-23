package com.letta.mobile.desktop.skills

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.chat.desktopChatJson
import io.ktor.client.HttpClient
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
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Desktop skills API. Skills are a first-class registry on this Letta server:
 * `GET /v1/skills` lists everything available, `GET /v1/agents/{id}/skills`
 * lists what a given agent has installed, and POST/DELETE on
 * `/v1/agents/{id}/skills` install/uninstall a skill for that agent.
 *
 * The `:desktop` module has no kotlinx-serialization compiler plugin, so
 * responses are parsed via the JSON element API (matching [DesktopCronApi]).
 */
class DesktopSkillApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient = createDesktopLettaHttpClient(),
) : AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    /** Every skill in the server registry. */
    suspend fun listSkills(): List<DesktopSkill> {
        val response = httpClient.get("$baseUrl/v1/skills") { applyAuth() }
        response.requireSuccess()
        return parseSkills(response.bodyAsText())
    }

    /** Skills installed on [agentId]. */
    suspend fun listAgentSkills(agentId: String): List<DesktopSkill> {
        val response = httpClient.get("$baseUrl/v1/agents/$agentId/skills") { applyAuth() }
        response.requireSuccess()
        return parseSkills(response.bodyAsText())
    }

    /** Install [skillName] onto [agentId]. */
    suspend fun installSkill(agentId: String, skillName: String) {
        val body = buildJsonObject { put("name", skillName) }
        val response = httpClient.post("$baseUrl/v1/agents/$agentId/skills") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(desktopChatJson.encodeToString(JsonObject.serializer(), body))
        }
        response.requireSuccess()
    }

    /** Remove [skillName] from [agentId]. */
    suspend fun uninstallSkill(agentId: String, skillName: String) {
        val encoded = skillName.encodeURLPathPart()
        val response = httpClient.delete("$baseUrl/v1/agents/$agentId/skills/$encoded") { applyAuth() }
        response.requireSuccess()
    }

    private fun parseSkills(body: String): List<DesktopSkill> {
        val root = desktopChatJson.parseToJsonElement(body)
        val array: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject -> root["skills"]?.jsonArray ?: root["data"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            DesktopSkill(
                name = name,
                version = obj["version"]?.jsonPrimitive?.contentOrNull,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                author = obj["author"]?.jsonPrimitive?.contentOrNull,
                installedCount = obj["installed_count"]?.jsonPrimitive?.intOrNull,
                tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
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
            throw IllegalStateException("Skill API ${status.value}: ${bodyAsText()}")
        }
    }
}

data class DesktopSkill(
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val author: String? = null,
    val installedCount: Int? = null,
    val tags: List<String> = emptyList(),
)
