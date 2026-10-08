package com.letta.mobile.data.secrets

import androidx.compose.runtime.Immutable

/** Where the vault's key list stands. */
@Immutable
sealed interface AgentVaultLoad {
    data object Idle : AgentVaultLoad

    data object Loading : AgentVaultLoad

    data object Loaded : AgentVaultLoad

    data class Failed(val message: String) : AgentVaultLoad
}

/**
 * The secret being added ([originalKey] null) or replaced. The value is typed into a masked field
 * and only drawn in clear while [showValue] is on.
 */
@Immutable
data class AgentSecretDraft(
    val originalKey: String? = null,
    val key: String = originalKey.orEmpty(),
    val value: SecretValue = SecretValue.Empty,
    val showValue: Boolean = false,
) {
    val isNew: Boolean get() = originalKey == null

    val normalizedKey: String get() = AgentSecretKeys.normalize(key)
}

/** Everything the shared vault page draws (letta-mobile-bzvro.25). */
@Immutable
data class AgentVaultState(
    val agentId: String? = null,
    val load: AgentVaultLoad = AgentVaultLoad.Idle,
    val secrets: List<AgentSecret> = emptyList(),
    /** Keys whose values the user asked to see. Cleared when the agent changes or the page closes. */
    val revealed: Set<String> = emptySet(),
    val draft: AgentSecretDraft? = null,
    val pendingDelete: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
) {
    /** Why the draft cannot be saved yet, or null when it can. */
    val draftProblem: String?
        get() {
            val draft = draft ?: return null
            return when {
                draft.normalizedKey.isEmpty() -> "Name the secret."
                !AgentSecretKeys.isValid(draft.normalizedKey) -> "Use letters, digits and underscores, not starting with a digit."
                draft.isNew && secrets.any { it.key == draft.normalizedKey } -> "${draft.normalizedKey} already exists; edit it instead."
                draft.value.isBlank -> "Enter a value."
                else -> null
            }
        }

    val canSaveDraft: Boolean get() = draft != null && draftProblem == null && !saving

    val emptyMessage: String?
        get() = when {
            agentId == null -> "Choose an agent to manage its secrets."
            load == AgentVaultLoad.Loaded && secrets.isEmpty() -> "This agent has no secrets yet."
            else -> null
        }

    /** The value [key] draws: the plaintext once revealed, else a fixed-width mask. */
    fun displayValue(key: String): String {
        val secret = secrets.firstOrNull { it.key == key } ?: return SecretValue.MASK
        return if (key in revealed) secret.value.reveal() else SecretValue.MASK
    }
}

/** What the shared vault page can ask of its controller. */
interface AgentVaultActions {
    fun refresh()

    fun toggleReveal(key: String)

    /** Masks every value again; hosts call it when the page goes away. */
    fun hideAll()

    fun startAdding()

    fun startEditing(key: String)

    fun updateDraftKey(key: String)

    fun updateDraftValue(value: String)

    fun toggleDraftValueVisible()

    fun cancelDraft()

    fun saveDraft()

    fun requestDelete(key: String)

    fun confirmDelete()

    fun cancelDelete()

    fun dismissError()
}
