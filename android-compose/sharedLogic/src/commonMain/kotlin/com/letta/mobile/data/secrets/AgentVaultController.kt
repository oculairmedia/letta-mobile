package com.letta.mobile.data.secrets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pure transitions of [AgentVaultState] (letta-mobile-bzvro.25). */
object AgentVaultReducer {
    /** A new agent: its predecessor's secrets, reveals and drafts are dropped at once. */
    fun withAgent(state: AgentVaultState, agentId: String?): AgentVaultState =
        if (state.agentId == agentId) state else AgentVaultState(agentId = agentId)

    fun loading(state: AgentVaultState): AgentVaultState = state.copy(load = AgentVaultLoad.Loading, error = null)

    fun loaded(state: AgentVaultState, agentId: String, secrets: List<AgentSecret>): AgentVaultState {
        if (state.agentId != agentId) return state
        val keys = secrets.map { it.key }.toSet()
        return state.copy(load = AgentVaultLoad.Loaded, secrets = secrets, revealed = state.revealed intersect keys)
    }

    fun loadFailed(state: AgentVaultState, agentId: String, message: String): AgentVaultState =
        if (state.agentId != agentId) state else state.copy(load = AgentVaultLoad.Failed(message))

    fun toggledReveal(state: AgentVaultState, key: String): AgentVaultState =
        state.copy(revealed = if (key in state.revealed) state.revealed - key else state.revealed + key)

    fun hiddenAll(state: AgentVaultState): AgentVaultState =
        state.copy(revealed = emptySet(), draft = state.draft?.copy(showValue = false))

    fun adding(state: AgentVaultState): AgentVaultState = state.copy(draft = AgentSecretDraft(), error = null)

    /** Replacing a value starts blank: the old value is never copied into an editable field. */
    fun editing(state: AgentVaultState, key: String): AgentVaultState =
        if (state.secrets.none { it.key == key }) state else state.copy(draft = AgentSecretDraft(originalKey = key), error = null)

    fun draftKey(state: AgentVaultState, key: String): AgentVaultState =
        state.copy(draft = state.draft?.takeIf { it.isNew }?.copy(key = key) ?: state.draft)

    fun draftValue(state: AgentVaultState, value: String): AgentVaultState =
        state.copy(draft = state.draft?.copy(value = SecretValue(value)))

    fun draftValueVisibility(state: AgentVaultState): AgentVaultState =
        state.copy(draft = state.draft?.let { it.copy(showValue = !it.showValue) })

    fun draftCancelled(state: AgentVaultState): AgentVaultState = state.copy(draft = null)

    fun deleteRequested(state: AgentVaultState, key: String): AgentVaultState =
        if (state.secrets.none { it.key == key }) state else state.copy(pendingDelete = key)

    fun deleteCancelled(state: AgentVaultState): AgentVaultState = state.copy(pendingDelete = null)

    fun saving(state: AgentVaultState): AgentVaultState = state.copy(saving = true, error = null)

    /** A batch landed: drafts and confirmations close; the list is re-read from the server. */
    fun applied(state: AgentVaultState, agentId: String): AgentVaultState =
        if (state.agentId != agentId) state else state.copy(saving = false, draft = null, pendingDelete = null)

    fun applyFailed(state: AgentVaultState, agentId: String, message: String): AgentVaultState =
        if (state.agentId != agentId) state else state.copy(saving = false, pendingDelete = null, error = message)

    fun errorDismissed(state: AgentVaultState): AgentVaultState = state.copy(error = null)
}

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
        agentId?.let(::load)
    }

    override fun refresh() {
        stateFlow.value.agentId?.let(::load)
    }

    override fun toggleReveal(key: String) = stateFlow.update { AgentVaultReducer.toggledReveal(it, key) }

    override fun hideAll() = stateFlow.update(AgentVaultReducer::hiddenAll)

    override fun startAdding() = stateFlow.update(AgentVaultReducer::adding)

    override fun startEditing(key: String) = stateFlow.update { AgentVaultReducer.editing(it, key) }

    override fun updateDraftKey(key: String) = stateFlow.update { AgentVaultReducer.draftKey(it, key) }

    override fun updateDraftValue(value: String) = stateFlow.update { AgentVaultReducer.draftValue(it, value) }

    override fun toggleDraftValueVisible() = stateFlow.update(AgentVaultReducer::draftValueVisibility)

    override fun cancelDraft() = stateFlow.update(AgentVaultReducer::draftCancelled)

    override fun saveDraft() {
        val state = stateFlow.value
        val draft = state.draft?.takeIf { state.canSaveDraft } ?: return
        apply(AgentSecretChanges(set = mapOf(draft.normalizedKey to draft.value)))
    }

    override fun requestDelete(key: String) = stateFlow.update { AgentVaultReducer.deleteRequested(it, key) }

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

    private fun apply(changes: AgentSecretChanges) {
        val agentId = stateFlow.value.agentId ?: return
        if (stateFlow.value.saving) return
        stateFlow.update(AgentVaultReducer::saving)
        applyJob = scope.launch {
            attempt(onFailure = { current, message -> AgentVaultReducer.applyFailed(current, agentId, message) }) {
                source.apply(agentId, changes)
                stateFlow.update { AgentVaultReducer.applied(it, agentId) }
                load(agentId)
            }
        }
    }

    private fun load(agentId: String) {
        stateFlow.update(AgentVaultReducer::loading)
        loadJob?.cancel()
        loadJob = scope.launch {
            attempt(onFailure = { current, message -> AgentVaultReducer.loadFailed(current, agentId, message) }) {
                val secrets = source.list(agentId)
                stateFlow.update { AgentVaultReducer.loaded(it, agentId, secrets) }
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
