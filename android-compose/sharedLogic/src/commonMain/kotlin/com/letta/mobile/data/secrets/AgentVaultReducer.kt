package com.letta.mobile.data.secrets

/** The agent a vault request was made for. */
data class VaultAgent(val agentId: String)

/** A vault request that failed, with the agent it was for and why. */
data class AgentVaultFailure(val agent: VaultAgent, val message: String)

/** Pure transitions of [AgentVaultState] (letta-mobile-bzvro.25). */
object AgentVaultReducer {
    /** A new agent: its predecessor's secrets, reveals and drafts are dropped at once. */
    fun withAgent(state: AgentVaultState, agentId: String?): AgentVaultState =
        if (state.agentId == agentId) state else AgentVaultState(agentId = agentId)

    fun loading(state: AgentVaultState): AgentVaultState = state.copy(load = AgentVaultLoad.Loading, error = null)

    fun loaded(state: AgentVaultState, agent: VaultAgent, secrets: List<AgentSecret>): AgentVaultState {
        if (state.agentId != agent.agentId) return state
        val keys = secrets.map { it.key }.toSet()
        return state.copy(load = AgentVaultLoad.Loaded, secrets = secrets, revealed = state.revealed intersect keys)
    }

    fun loadFailed(state: AgentVaultState, failure: AgentVaultFailure): AgentVaultState =
        if (state.agentId != failure.agent.agentId) state else state.copy(load = AgentVaultLoad.Failed(failure.message))

    fun toggledReveal(state: AgentVaultState, key: SecretKey): AgentVaultState =
        state.copy(revealed = if (key.name in state.revealed) state.revealed - key.name else state.revealed + key.name)

    fun hiddenAll(state: AgentVaultState): AgentVaultState =
        state.copy(revealed = emptySet(), draft = state.draft?.copy(showValue = false))

    fun adding(state: AgentVaultState): AgentVaultState = state.copy(draft = AgentSecretDraft(), error = null)

    /** Replacing a value starts blank: the old value is never copied into an editable field. */
    fun editing(state: AgentVaultState, key: SecretKey): AgentVaultState =
        if (!state.holds(key)) state else state.copy(draft = AgentSecretDraft(originalKey = key.name), error = null)

    fun draftKey(state: AgentVaultState, key: String): AgentVaultState =
        state.copy(draft = state.draft?.takeIf { it.isNew }?.copy(key = key) ?: state.draft)

    fun draftValue(state: AgentVaultState, value: String): AgentVaultState =
        state.copy(draft = state.draft?.copy(value = SecretValue(value)))

    fun draftValueVisibility(state: AgentVaultState): AgentVaultState =
        state.copy(draft = state.draft?.let { it.copy(showValue = !it.showValue) })

    fun draftCancelled(state: AgentVaultState): AgentVaultState = state.copy(draft = null)

    fun deleteRequested(state: AgentVaultState, key: SecretKey): AgentVaultState =
        if (!state.holds(key)) state else state.copy(pendingDelete = key.name)

    fun deleteCancelled(state: AgentVaultState): AgentVaultState = state.copy(pendingDelete = null)

    fun saving(state: AgentVaultState): AgentVaultState = state.copy(saving = true, error = null)

    /** A batch landed: drafts and confirmations close; the list is re-read from the server. */
    fun applied(state: AgentVaultState, agent: VaultAgent): AgentVaultState =
        if (state.agentId != agent.agentId) state else state.copy(saving = false, draft = null, pendingDelete = null)

    fun applyFailed(state: AgentVaultState, failure: AgentVaultFailure): AgentVaultState =
        if (state.agentId != failure.agent.agentId) state else state.copy(saving = false, pendingDelete = null, error = failure.message)

    fun errorDismissed(state: AgentVaultState): AgentVaultState = state.copy(error = null)

    private fun AgentVaultState.holds(key: SecretKey): Boolean = secrets.any { it.key == key.name }
}
