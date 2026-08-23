package com.letta.mobile.data.api

import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.model.SlashCommandsResponse
import com.letta.mobile.data.repository.api.SlashCommandRemoteSource
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

@Singleton
class SlashCommandApi @Inject constructor(
    private val apiClient: LettaApiClient,
) : SlashCommandRemoteSource {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun listGlobal(): List<SlashCommand> {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/v1/slash-commands")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        return response.body<SlashCommandsResponse>().commands
    }

    override suspend fun listForAgent(agentId: String): List<SlashCommand> {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/v1/agents/$agentId/slash-commands")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        return response.body<SlashCommandsResponse>().commands
    }

    override suspend fun installToAgent(agentId: String, skillName: String) {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/v1/agents/$agentId/skills") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("name" to JsonPrimitive(skillName))))
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
    }

    override suspend fun uninstallFromAgent(agentId: String, skillName: String) {
        val (client, baseUrl) = apiClient.session()
        val encoded = skillName.encodeURLPathPart()
        val response = client.delete("$baseUrl/v1/agents/$agentId/skills/$encoded")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
    }

    override suspend fun getGoalStatus(agentId: String): GoalStatusResponse {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/v1/agents/$agentId/goal")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        return response.body<GoalStatusResponse>()
    }

    override suspend fun executeGoalCommand(agentId: String, command: String): String {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/v1/agents/$agentId/goal/command") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("command" to JsonPrimitive(command))))
        }
        val bodyText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw IllegalStateException(bodyText)
        }
        val body = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
        return (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Goal command executed."
    }
}
