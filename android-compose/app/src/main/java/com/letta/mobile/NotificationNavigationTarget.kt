package com.letta.mobile

import android.content.Context
import android.content.Intent
import com.letta.mobile.feature.chat.route.AgentChatRoute

data class NotificationNavigationTarget(
    val agentId: String,
    val conversationId: String,
    val agentName: String? = null,
) : AppLaunchTarget {
    override fun toRoute(): AgentChatRoute = AgentChatRoute(
        agentId = agentId,
        agentName = agentName,
        conversationId = conversationId,
    )

    /**
     * letta-mobile-by4vm.31: the intent carries only an opaque single-use token issued by
     * [NotificationLaunchTokens]; the agent/conversation never travel as extras.
     */
    fun createIntent(context: Context): Intent {
        val token = NotificationLaunchTokens.issue(context, this)
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LAUNCH_TOKEN, token)
        }
    }

    companion object {
        const val EXTRA_LAUNCH_TOKEN = "notification_launch_token"

        /** Automation-only raw extras (device scripts); see [com.letta.mobile.debug.AutomationImportGate]. */
        const val EXTRA_AGENT_ID = "notification_agent_id"
        const val EXTRA_CONVERSATION_ID = "notification_conversation_id"
        const val EXTRA_AGENT_NAME = "notification_agent_name"

        /**
         * Resolves a notification token (consumed once, then removed from [intent]). Raw
         * agent/conversation extras are honoured only when [automationExtrasEnabled].
         */
        fun fromIntent(
            intent: Intent?,
            context: Context,
            automationExtrasEnabled: Boolean,
        ): NotificationNavigationTarget? {
            intent ?: return null
            val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
            if (token != null) {
                intent.removeExtra(EXTRA_LAUNCH_TOKEN)
                return NotificationLaunchTokens.redeem(context, token)
            }
            return if (automationExtrasEnabled) fromAutomationExtras(intent) else null
        }

        private fun fromAutomationExtras(intent: Intent): NotificationNavigationTarget? {
            val agentId = intent.getStringExtra(EXTRA_AGENT_ID)?.takeIf { it.isNotBlank() } ?: return null
            val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf { it.isNotBlank() } ?: return null
            val agentName = intent.getStringExtra(EXTRA_AGENT_NAME)?.takeIf { it.isNotBlank() }
            return NotificationNavigationTarget(
                agentId = agentId,
                conversationId = conversationId,
                agentName = agentName,
            )
        }
    }
}
