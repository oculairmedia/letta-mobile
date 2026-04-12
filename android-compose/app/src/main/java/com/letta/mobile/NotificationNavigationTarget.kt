package com.letta.mobile

import android.content.Context
import android.content.Intent
import com.letta.mobile.ui.navigation.AgentChatRoute

data class NotificationNavigationTarget(
    val agentId: String,
    val conversationId: String,
) {
    fun toRoute(): AgentChatRoute = AgentChatRoute(
        agentId = agentId,
        conversationId = conversationId,
    )

    fun createIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AGENT_ID, agentId)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
    }

    companion object {
        const val EXTRA_AGENT_ID = "notification_agent_id"
        const val EXTRA_CONVERSATION_ID = "notification_conversation_id"

        fun fromIntent(intent: Intent?): NotificationNavigationTarget? {
            val agentId = intent?.getStringExtra(EXTRA_AGENT_ID)?.takeIf { it.isNotBlank() } ?: return null
            val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf { it.isNotBlank() } ?: return null
            return NotificationNavigationTarget(agentId = agentId, conversationId = conversationId)
        }
    }
}
