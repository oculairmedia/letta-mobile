package com.letta.mobile.data.repository

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.model.SlashCommandsResponse
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlashCommandRepository @Inject constructor(
    private val apiClient: LettaApiClient,
) : ISlashCommandRepository {
    override suspend fun listGlobal(): Result<List<SlashCommand>> = fetch("/v1/slash-commands")

    override suspend fun listForAgent(agentId: String): Result<List<SlashCommand>> =
        fetch("/v1/agents/$agentId/slash-commands")

    override suspend fun installToAgent(agentId: String, skillName: String): Result<Unit> = runCatching {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/v1/agents/$agentId/skills") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("name" to JsonPrimitive(skillName))))
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        Unit
    }

    override suspend fun uninstallFromAgent(agentId: String, skillName: String): Result<Unit> = runCatching {
        val (client, baseUrl) = apiClient.session()
        val response = client.delete("$baseUrl/v1/agents/$agentId/skills/$skillName")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        Unit
    }

    override suspend fun getGoalStatus(agentId: String): Result<GoalStatusResponse> = runCatching {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/v1/agents/$agentId/goal")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        response.body<GoalStatusResponse>()
    }

    override suspend fun executeGoalCommand(agentId: String, command: String): Result<String> = runCatching {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/v1/agents/$agentId/goal/command") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("command" to JsonPrimitive(command))))
        }
        val bodyText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw IllegalStateException(bodyText)
        }
        val body = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
        body?.get("message")?.jsonPrimitive?.content ?: "Goal command executed."
    }

    private suspend fun fetch(path: String): Result<List<SlashCommand>> = runCatching {
        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl$path")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        response.body<SlashCommandsResponse>().commands
    }
}
