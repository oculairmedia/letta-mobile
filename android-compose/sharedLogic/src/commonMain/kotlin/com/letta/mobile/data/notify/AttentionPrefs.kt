package com.letta.mobile.data.notify

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-bzvro.27 (F27): the person's notification choices. A muted agent posts nothing at
 * all (approvals, finished replies, message notifications); [approvalNotificationsEnabled] turns
 * approval notifications off for every agent.
 */
data class AttentionPrefs(
    val mutedAgentIds: Set<String> = emptySet(),
    val approvalNotificationsEnabled: Boolean = true,
) {
    fun isMuted(agentId: String): Boolean = agentId in mutedAgentIds
}

/** Read and change [AttentionPrefs]. Platform-neutral; hosts only supply the storage. */
interface AttentionPrefsRepository {
    val prefs: StateFlow<AttentionPrefs>

    fun setAgentMuted(agentId: String, muted: Boolean)

    fun setApprovalNotificationsEnabled(enabled: Boolean)
}

/**
 * [AttentionPrefsRepository] kept in the app's settings store, so mutes survive a restart. Android
 * binds its SharedPreferences store and desktop its own; neither holds a copy of this logic.
 */
class StoredAttentionPrefs(
    private val store: SecureSettingsStore,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) : AttentionPrefsRepository {
    private val _prefs = MutableStateFlow(load())
    override val prefs: StateFlow<AttentionPrefs> = _prefs.asStateFlow()

    override fun setAgentMuted(agentId: String, muted: Boolean) {
        if (agentId.isBlank()) return
        _prefs.update { it.copy(mutedAgentIds = if (muted) it.mutedAgentIds + agentId else it.mutedAgentIds - agentId) }
        persist()
    }

    override fun setApprovalNotificationsEnabled(enabled: Boolean) {
        _prefs.update { it.copy(approvalNotificationsEnabled = enabled) }
        persist()
    }

    private fun load(): AttentionPrefs = AttentionPrefs(
        mutedAgentIds = store.getString(mutedKey)
            ?.split(SEPARATOR)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty(),
        approvalNotificationsEnabled = store.getString(approvalsKey) != DISABLED,
    )

    private fun persist() {
        val current = _prefs.value
        if (current.mutedAgentIds.isEmpty()) {
            store.remove(mutedKey)
        } else {
            store.putString(mutedKey, current.mutedAgentIds.sorted().joinToString(SEPARATOR))
        }
        if (current.approvalNotificationsEnabled) store.remove(approvalsKey) else store.putString(approvalsKey, DISABLED)
    }

    private val mutedKey get() = "$keyPrefix.mutedAgents"
    private val approvalsKey get() = "$keyPrefix.approvals"

    companion object {
        const val DEFAULT_KEY_PREFIX = "notifications.attention"
        private const val SEPARATOR = "\n"
        private const val DISABLED = "off"
    }
}
