package com.letta.mobile.data.secrets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Platform-neutral controller behind the shared vault page (letta-mobile-bzvro.25): lists an
 * agent's secrets, reveals one value on request, and adds, replaces or removes secrets with one
 * `secret_apply` each. Values exist only in this controller's memory and on the server.
 */
class AgentVaultController(
    private val source: AgentSecretsSource,
    private val scope: CoroutineScope,
) : AgentVaultActions, AutoCloseable {
    private val stateFlow = MutableStateFlow(AgentVaultState())
    val state: StateFlow<AgentVaultState> = stateFlow.asStateFlow()

    private var loadJob: Job? = null
    private var applyJob: Job? = null

    fun selectAgent(agentId: String?) {
        if (agentId == stateFlow.value.agentId) return
        loadJob?.cancel()
        applyJob?.cancel()
        stateFlow.update { AgentVaultReducer.withAgent(it, agentId) }
        load()
    }

    override fun refresh() = load()

    override fun toggleReveal(key: SecretKey) = stateFlow.update { AgentVaultReducer.toggledReveal(it, key) }

    override fun hideAll() = stateFlow.update(AgentVaultReducer::hiddenAll)

    override fun startAdding() = stateFlow.update(AgentVaultReducer::adding)

    override fun startEditing(key: SecretKey) = stateFlow.update { AgentVaultReducer.editing(it, key) }

    override fun updateDraftKey(key: String) = stateFlow.update { AgentVaultReducer.draftKey(it, key) }

    override fun updateDraftValue(value: String) = stateFlow.update { AgentVaultReducer.draftValue(it, value) }

    override fun toggleDraftValueVisible() = stateFlow.update(AgentVaultReducer::draftValueVisibility)

    override fun cancelDraft() = stateFlow.update(AgentVaultReducer::draftCancelled)

    override fun saveDraft() {
        val state = stateFlow.value
        val draft = state.draft?.takeIf { state.canSaveDraft } ?: return
        apply(AgentSecretChanges(set = mapOf(draft.normalizedKey to draft.value)))
    }

    override fun requestDelete(key: SecretKey) = stateFlow.update { AgentVaultReducer.deleteRequested(it, key) }

    override fun confirmDelete() {
        val key = stateFlow.value.pendingDelete ?: return
        apply(AgentSecretChanges(unset = setOf(key)))
    }

    override fun cancelDelete() = stateFlow.update(AgentVaultReducer::deleteCancelled)

    override fun dismissError() = stateFlow.update(AgentVaultReducer::errorDismissed)

    /** Drops every value held in memory; a later [selectAgent] loads afresh. */
    override fun close() {
        loadJob?.cancel()
        applyJob?.cancel()
        stateFlow.value = AgentVaultState()
    }

    private fun currentAgent(): VaultAgent? = stateFlow.value.agentId?.let(::VaultAgent)

    private fun apply(changes: AgentSecretChanges) {
        val agent = currentAgent() ?: return
        if (stateFlow.value.saving) return
        stateFlow.update(AgentVaultReducer::saving)
        applyJob = scope.launch {
            attempt(onFailure = { current, message -> AgentVaultReducer.applyFailed(current, AgentVaultFailure(agent, message)) }) {
                source.apply(agent.agentId, changes)
                stateFlow.update { AgentVaultReducer.applied(it, agent) }
                load()
            }
        }
    }

    /** Re-reads the current agent's secrets; nothing when no agent is shown. */
    private fun load() {
        val agent = currentAgent() ?: return
        stateFlow.update(AgentVaultReducer::loading)
        loadJob?.cancel()
        loadJob = scope.launch {
            attempt(onFailure = { current, message -> AgentVaultReducer.loadFailed(current, AgentVaultFailure(agent, message)) }) {
                val secrets = source.list(agent.agentId)
                stateFlow.update { AgentVaultReducer.loaded(it, agent, secrets) }
            }
        }
    }

    private suspend fun attempt(
        onFailure: (AgentVaultState, String) -> AgentVaultState,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = (error as? AgentSecretsException)?.message ?: "The secrets request failed."
            stateFlow.update { onFailure(it, message) }
        }
    }
}
