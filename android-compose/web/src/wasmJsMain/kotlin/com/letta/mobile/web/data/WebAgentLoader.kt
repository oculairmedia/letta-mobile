package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.agents.AgentItemState
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object WebAgentLoader {
    private val client = HttpClient(Js)

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Resolve candidate HTTP API base URLs from a LettaConfig serverUrl.
     */
    fun resolveCandidateUrls(serverUrl: String, mode: LettaConfig.Mode): List<String> {
        val trimmed = serverUrl.trim()
        val candidates = mutableListOf<String>()

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            candidates.add(trimmed.removeSuffix("/"))
        } else if (trimmed.startsWith("iroh://")) {
            // Format: iroh://<node_id>@<ip>:<port> or iroh://<node_id>@<ip>
            val atSplit = trimmed.substringAfter("iroh://").substringAfter("@", "")
            if (atSplit.isNotBlank()) {
                val host = atSplit.substringBefore(":")
                val port = atSplit.substringAfter(":", "").takeIf { it.isNotBlank() }

                // If a QUIC port like 4501 was given, try standard Letta HTTP port 8283 first, then the specified port
                if (port != null && port != "8283" && port != "8000") {
                    candidates.add("http://$host:8283")
                    candidates.add("http://$host:$port")
                } else if (port != null) {
                    candidates.add("http://$host:$port")
                } else {
                    candidates.add("http://$host:8283")
                    candidates.add("http://$host:8000")
                }
            }
        }

        when (mode) {
            LettaConfig.Mode.CLOUD -> candidates.add("https://app.letta.com")
            LettaConfig.Mode.SELF_HOSTED -> {
                candidates.add("http://127.0.0.1:8283")
                candidates.add("http://localhost:8283")
            }
            LettaConfig.Mode.LOCAL -> {
                candidates.add("http://127.0.0.1:8283")
                candidates.add("http://localhost:8283")
            }
        }
        return candidates.distinct()
    }

    /**
     * Fetch list of agents from the connected Letta backend server.
     */
    suspend fun fetchAgents(config: LettaConfig): Result<List<AgentItemState>> {
        val candidates = resolveCandidateUrls(config.serverUrl, config.mode)
        var lastError: Throwable? = null

        for (baseUrl in candidates) {
            val url = "$baseUrl/v1/agents"
            try {
                println("Attempting to fetch agents from: $url")
                val response = client.get(url) {
                    config.accessToken?.let { token ->
                        if (token.isNotBlank()) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    }
                }

                val body = response.bodyAsText()
                println("Agents response from $url: $body")

                val jsonElement = jsonParser.parseToJsonElement(body)
                val agentList = mutableListOf<AgentItemState>()

                if (jsonElement is kotlinx.serialization.json.JsonArray) {
                    for (item in jsonElement) {
                        val obj = item.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: "agent-${agentList.size + 1}"
                        val name = obj["name"]?.jsonPrimitive?.content ?: "Agent ${agentList.size + 1}"
                        val description = obj["description"]?.jsonPrimitive?.content
                        val model = obj["model"]?.jsonPrimitive?.content ?: "letta/letta-free"

                        agentList.add(
                            AgentItemState(
                                id = id,
                                name = name,
                                description = description,
                                model = model,
                                isOnline = true,
                            )
                        )
                    }
                    if (agentList.isNotEmpty()) {
                        return Result.success(agentList)
                    }
                }
            } catch (t: Throwable) {
                println("Fetch failed for $url: ${t.message}")
                lastError = t
            }
        }

        return Result.failure(
            lastError ?: Exception("Could not connect to any candidate endpoint: ${candidates.joinToString()}")
        )
    }
}
