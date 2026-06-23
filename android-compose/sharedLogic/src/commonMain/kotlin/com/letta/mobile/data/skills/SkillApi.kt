package com.letta.mobile.data.skills

import com.letta.mobile.data.model.LettaConfig
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
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-neutral skills API. Skills are a first-class registry on the Letta
 * server: `GET /v1/skills` lists the registry, `GET /v1/agents/{id}/skills`
 * lists what an agent has installed, and POST/DELETE on `/v1/agents/{id}/skills`
 * install/uninstall a skill for that agent.
 *
 * Lives in commonMain so every host shares one implementation; the platform
 * supplies the Ktor [HttpClient] (with content negotiation installed).
 */
class SkillApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient,
) : AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    /** Every skill in the server registry. */
    suspend fun listSkills(): List<Skill> {
        val response = httpClient.get("$baseUrl/v1/skills") { applyAuth() }
        response.requireSuccess()
        return response.body<SkillsResponse>().skills
    }

    /** Skills installed on [agentId]. */
    suspend fun listAgentSkills(agentId: String): List<Skill> {
        val response = httpClient.get("$baseUrl/v1/agents/$agentId/skills") { applyAuth() }
        response.requireSuccess()
        return response.body<SkillsResponse>().skills
    }

    /** Install [skillName] onto [agentId]. */
    suspend fun installSkill(agentId: String, skillName: String) {
        val response = httpClient.post("$baseUrl/v1/agents/$agentId/skills") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(InstallSkillRequest(skillName))
        }
        response.requireSuccess()
    }

    /** Remove [skillName] from [agentId]. */
    suspend fun uninstallSkill(agentId: String, skillName: String) {
        val encoded = skillName.encodeURLPathPart()
        val response = httpClient.delete("$baseUrl/v1/agents/$agentId/skills/$encoded") { applyAuth() }
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
            throw IllegalStateException("Skill API ${status.value}: ${bodyAsText()}")
        }
    }
}

@Serializable
data class Skill(
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val author: String? = null,
    @SerialName("installed_count") val installedCount: Int? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
private data class SkillsResponse(
    val skills: List<Skill> = emptyList(),
)

@Serializable
private data class InstallSkillRequest(
    val name: String,
)
