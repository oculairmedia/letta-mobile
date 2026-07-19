package com.letta.mobile.desktop

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import kotlinx.coroutines.flow.first

private data class DesktopAgentResolveSources(
    val agentIds: Set<String>,
    val irohDirectory: IrohAdminRpcAgentDirectory?,
    val httpAgentRepository: () -> IAgentRepository,
)

private data class ResolveDesktopAgentFieldParams<T : Any>(
    val sources: DesktopAgentResolveSources,
    val fromAgent: (Agent) -> T?,
    val refreshBeforeResolve: Boolean = false,
)

private suspend fun <T : Any> resolveDesktopAgentField(
    params: ResolveDesktopAgentFieldParams<T>,
): Map<String, T> {
    val sources = params.sources
    val irohDirectory = sources.irohDirectory
    if (irohDirectory != null) {
        return resolveFromIrohDirectory(irohDirectory, params.fromAgent)
    }
    return resolveFromHttpRepository(params)
}

private suspend fun <T : Any> resolveFromIrohDirectory(
    irohDirectory: IrohAdminRpcAgentDirectory,
    fromAgent: (Agent) -> T?,
): Map<String, T> =
    runCatching { irohDirectory.listAgents() }.getOrDefault(emptyList())
        .mapNotNull { agent -> fromAgent(agent)?.let { agent.id.value to it } }
        .toMap()

private suspend fun <T : Any> resolveFromHttpRepository(
    params: ResolveDesktopAgentFieldParams<T>,
): Map<String, T> {
    val sources = params.sources
    val agentRepository = sources.httpAgentRepository()
    if (params.refreshBeforeResolve) {
        runCatching { agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS) }
    }
    val resolved = mutableMapOf<String, T>()
    agentRepository.agents.value.forEach { agent ->
        params.fromAgent(agent)?.let { resolved[agent.id.value] = it }
    }
    sources.agentIds.filter { it !in resolved }.forEach { id ->
        val value = agentRepository.getCachedAgent(id)?.let(params.fromAgent)
            ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.let(params.fromAgent)
        value?.let { resolved[id] = it }
    }
    return resolved
}

private fun nonBlank(value: String?): String? = value?.takeIf { it.isNotBlank() }

/**
 * Resolves agent id -> display name for the chat shell. Over iroh:// there is
 * no HTTP agent repository, so names come from the admin_rpc agent directory;
 * otherwise from the cached repository, fetching any still-unresolved id
 * directly. [httpAgentRepository] is only evaluated on the HTTP path.
 */
internal suspend fun resolveDesktopAgentNames(
    agentIds: Set<String>,
    irohDirectory: IrohAdminRpcAgentDirectory?,
    httpAgentRepository: () -> IAgentRepository,
): Map<String, String> = resolveDesktopAgentField(
    ResolveDesktopAgentFieldParams(
        sources = DesktopAgentResolveSources(agentIds, irohDirectory, httpAgentRepository),
        fromAgent = { agent -> nonBlank(agent.name) },
        refreshBeforeResolve = true,
    ),
)

/** Agent id -> model handle, mirroring [resolveDesktopAgentNames]. */
internal suspend fun resolveDesktopAgentModels(
    agentIds: Set<String>,
    irohDirectory: IrohAdminRpcAgentDirectory?,
    httpAgentRepository: () -> IAgentRepository,
): Map<String, String> = resolveDesktopAgentField(
    ResolveDesktopAgentFieldParams(
        sources = DesktopAgentResolveSources(agentIds, irohDirectory, httpAgentRepository),
        fromAgent = { agent -> nonBlank(agent.model) },
    ),
)
