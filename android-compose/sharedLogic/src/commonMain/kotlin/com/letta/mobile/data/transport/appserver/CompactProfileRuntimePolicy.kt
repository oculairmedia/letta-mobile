package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CompactAgentProfile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The run-time half of [CompactAgentProfile]. letta-code 0.34.14 has no agent-stored switch for the
 * skills catalog or the client toolset: `skill_sources` is per `runtime_start` and
 * `client_tool_allowlist` per input. So every [DefaultAppServerClient] (every platform, every hop)
 * learns which agents carry [CompactAgentProfile.TAG] from the agent records it sees and sends their
 * runs `skill_sources: []` and their inputs `client_tool_allowlist: []`: no skills catalog in the
 * system prompt, no tool schemas in the request.
 */
class CompactProfileRuntimePolicy {
    private val lock = SynchronizedObject()
    private val compactAgents = mutableSetOf<AgentId>()

    /** Records whether [agent] (an agent record from the server) is compact; a record without tags says nothing. */
    fun learn(agent: JsonObject?) {
        val id = (agent?.get("id") as? JsonPrimitive)?.contentOrNull ?: return
        val tags = (agent["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return
        val compact = CompactAgentProfile.isCompact(tags)
        val agentId = AgentId(id)
        synchronized(lock) { if (compact) compactAgents += agentId else compactAgents -= agentId }
    }

    fun isCompact(agentId: AgentId?): Boolean = agentId != null && synchronized(lock) { agentId in compactAgents }

    /** [command] with no skill sources when its agent is known to be compact. */
    fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerCommand.RuntimeStart =
        if (isCompact(command.agentId?.let(::AgentId))) command.copy(skillSources = emptyList()) else command

    /**
     * The runtime_start to send again once [response] showed a compact agent that [sent] did not
     * keep free of skills; null when none is needed. Resending only updates the run's skill sources.
     */
    fun followUp(
        sent: AppServerCommand.RuntimeStart,
        response: AppServerInboundFrame.RuntimeStartResponse,
    ): AppServerCommand.RuntimeStart? {
        if (!response.success) return null
        learn(response.agent)
        val agentId = response.runtime?.agentId ?: sent.agentId
        if (!isCompact(agentId?.let(::AgentId)) || sent.skillSources == emptyList<String>()) return null
        return sent.copy(
            requestId = "${sent.requestId}$FOLLOW_UP_SUFFIX",
            agentId = agentId,
            createAgent = null,
            conversationId = response.runtime?.conversationId ?: sent.conversationId,
            createConversation = null,
            skillSources = emptyList(),
        )
    }

    /** [command] with no client tools offered when its agent is compact. */
    fun input(command: AppServerCommand.Input): AppServerCommand.Input {
        val payload = command.payload as? AppServerInputPayload.CreateMessage ?: return command
        if (!isCompact(AgentId(command.runtime.agentId))) return command
        return command.copy(payload = payload.copy(clientToolAllowlist = emptyList()))
    }

    private companion object {
        const val FOLLOW_UP_SUFFIX = "-compact"
    }
}
