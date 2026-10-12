package com.letta.mobile.testutil

import com.letta.mobile.data.notify.AttentionPrefs
import com.letta.mobile.data.notify.AttentionPrefsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [AttentionPrefsRepository] for unit tests (letta-mobile-bzvro.27). */
class FakeAttentionPrefs(initial: AttentionPrefs = AttentionPrefs()) : AttentionPrefsRepository {
    override val prefs = MutableStateFlow(initial)

    override fun setAgentMuted(agentId: String, muted: Boolean) {
        prefs.update { it.copy(mutedAgentIds = if (muted) it.mutedAgentIds + agentId else it.mutedAgentIds - agentId) }
    }

    override fun setApprovalNotificationsEnabled(enabled: Boolean) {
        prefs.update { it.copy(approvalNotificationsEnabled = enabled) }
    }
}
