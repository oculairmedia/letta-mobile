package com.letta.mobile.domain

import android.util.Log
import com.letta.mobile.data.api.AgentApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.paging.AgentPagingSource
import com.letta.mobile.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdminAgentManager @Inject constructor(
    private val agentApi: AgentApi,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "AdminAgentManager"
        const val ADMIN_TAG = "letta-mobile-admin"
        private const val ADMIN_NAME = "Letta Mobile Admin"
        private const val ADMIN_DESCRIPTION = "Server administration assistant for Letta Mobile"
        private const val LOOKUP_PAGE_SIZE = 50
        private const val FALLBACK_FULL_FETCH_LIMIT = 5_000
        private val ADMIN_SYSTEM_PROMPT = """
            You are the Letta Mobile server administrator agent. You help the user manage their Letta server from their mobile device.
            
            Your capabilities:
            - Report on server status and health
            - List and describe available agents
            - Help create and configure new agents
            - Explain agent capabilities, tools, and memory
            - Assist with troubleshooting
            
            Keep responses concise and mobile-friendly. Use bullet points for lists.
            When asked about server status, report what you know about the agents and tools available.
        """.trimIndent()
    }

    suspend fun ensureAdminAgent(): Agent {
        // 1. Check persisted ID first
        val existingId = settingsRepository.adminAgentId.value
        if (existingId != null) {
            try {
                val agent = agentApi.getAgent(AgentId(existingId))
                Log.d(TAG, "Found admin agent by saved ID: ${agent.id}")
                return agent
            } catch (e: Exception) {
                Log.w(TAG, "Saved admin agent ID invalid, clearing", e)
                settingsRepository.setAdminAgentId(null)
            }
        }

        // 2. Search ALL agents for one with our tag or name (belt + suspenders)
        try {
            val allAgents = listAllAgentsForLookup()
            val byTag = allAgents.find { agent ->
                agent.tags.contains(ADMIN_TAG)
            }
            if (byTag != null) {
                Log.d(TAG, "Found admin agent by tag: ${byTag.id}")
                settingsRepository.setAdminAgentId(byTag.id.value)
                return byTag
            }
            val byName = allAgents.find { it.name == ADMIN_NAME }
            if (byName != null) {
                Log.d(TAG, "Found admin agent by name: ${byName.id}")
                settingsRepository.setAdminAgentId(byName.id.value)
                return byName
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to search for existing admin agent", e)
        }

        // 3. Create only if truly not found
        Log.d(TAG, "No admin agent found, creating new one")
        val created = agentApi.createAgent(
            AgentCreateParams(
                name = ADMIN_NAME,
                description = ADMIN_DESCRIPTION,
                model = "anthropic-claude-max/opus-4-6-reasoning-medium",
                embedding = "ollama/dengcao/Qwen3-Embedding-4B:Q4_K_M",
                system = ADMIN_SYSTEM_PROMPT,
                tags = listOf(ADMIN_TAG),
                includeBaseTools = true,
                enableSleeptime = false,
                memoryBlocks = listOf(
                    BlockCreateParams(label = "persona", value = "I am a Letta server admin assistant."),
                    BlockCreateParams(label = "human", value = "The user is a Letta Mobile app user managing their server."),
                ),
            )
        )
        settingsRepository.setAdminAgentId(created.id.value)
        Log.d(TAG, "Created admin agent: ${created.id}")
        return created
    }

    private suspend fun listAllAgentsForLookup(): List<Agent> {
        val merged = mutableListOf<Agent>()
        var offset = AgentPagingSource.INITIAL_OFFSET
        while (true) {
            val page = agentApi.listAgents(limit = LOOKUP_PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break

            val existingIds = merged.asSequence().map { it.id }.toSet()
            val newAgents = page.filter { it.id !in existingIds }
            if (newAgents.isEmpty()) {
                Log.w(TAG, "Agent lookup offset pagination made no progress; falling back to count-sized fetch")
                return listAllAgentsWithoutOffsetFallback()
            }

            merged += newAgents
            if (page.size < LOOKUP_PAGE_SIZE) break
            offset += page.size
        }
        return merged
    }

    private suspend fun listAllAgentsWithoutOffsetFallback(): List<Agent> {
        val fallbackLimit = runCatching { agentApi.countAgents() }
            .getOrDefault(FALLBACK_FULL_FETCH_LIMIT)
            .coerceAtLeast(LOOKUP_PAGE_SIZE)
        return agentApi.listAgents(limit = fallbackLimit, offset = null)
            .distinctBy { it.id }
    }
}
