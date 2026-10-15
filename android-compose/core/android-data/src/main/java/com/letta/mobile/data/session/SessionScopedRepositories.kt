package com.letta.mobile.data.session

import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository

/**
 * letta-mobile-by4vm.88: the bindings whose forwarding differs from the [sessionScoped] default;
 * shared by the Hilt module and the backend-switch tests.
 */
fun SessionManager.scopedAgentRepository(): IAgentRepository = sessionScoped(
    IAgentRepository::class,
    // Wraps a caller-supplied operation; the old proxy never cancelled it on a switch.
    SessionForwarding(unchecked = setOf("checkpointAndRestoreConfig")),
) { it.agentRepository }

fun SessionManager.scopedAllConversationsRepository(): IAllConversationsRepository = sessionScoped(
    IAllConversationsRepository::class,
    SessionForwarding(retryOnSwitch = setOf("refresh", "refreshIfStale")),
) { it.allConversationsRepository }

/** The current graph's per-backend caches, for [BackendSwitchInvalidator]. */
fun SessionManager.sessionBackendScopedCaches(): Set<BackendScopedCache> = setOf(
    CurrentGraphCache("agents") { clearBackendScopedCache(current.agentRepository) },
    CurrentGraphCache("conversations") { clearBackendScopedCache(current.conversationRepository) },
    CurrentGraphCache("allConversations") { current.allConversationsRepository.clearForBackendSwitch() },
    CurrentGraphCache("passages") { clearBackendScopedCache(current.passageRepository) },
)

internal class CurrentGraphCache(
    val name: String,
    private val clear: suspend () -> Unit,
) : BackendScopedCache {
    override suspend fun clearForBackendSwitch() = clear()
}
