package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview

/**
 * Agents for local-runtime (embedded LettaCode) mode, durably persisted on
 * device. The Room agent cache is wiped on every session-graph creation, so
 * local agents must round-trip through this source to survive app restarts.
 *
 * Implemented by the app layer's local backend store; repositories route to
 * it instead of the remote API when the active config binds to the local
 * runtime.
 */
interface LocalRuntimeAgentSource {
    suspend fun listAgents(): List<Agent>

    suspend fun persistAgent(agent: Agent)

    /**
     * Approximate context utilization for a local agent, derived from the
     * on-disk transcript and agent record (letta.js does the same chars/4
     * estimate internally). Null when nothing is known about the agent yet.
     */
    suspend fun contextWindowOverview(agentId: AgentId): ContextWindowOverview?
}
