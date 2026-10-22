package com.letta.mobile

import android.content.Context
import android.content.Intent
import ca.oculair.meridian.BuildConfig
import com.letta.mobile.debug.AutomationImportGate

/** Where an intent opens the app: a chat, a share, the agent directory or the pairing scanner. */
sealed interface AppLaunchTarget {
    companion object {
        /**
         * MainActivity is exported: every extra here may come from another app.
         * letta-mobile-by4vm.31: automation extras only count on non-shipping build types.
         */
        fun fromIntent(
            intent: Intent?,
            context: Context,
            automationExtrasEnabled: Boolean = AutomationImportGate.automationLaunchExtrasEnabled(BuildConfig.BUILD_TYPE),
        ): AppLaunchTarget? {
            return NotificationNavigationTarget.fromIntent(intent, context, automationExtrasEnabled)
                ?: ShareLaunchTarget.fromIntent(intent)
                ?: AgentListLaunchTarget.fromIntent(intent, automationExtrasEnabled)
        }
    }
}

data object AgentListLaunchTarget : AppLaunchTarget {
    private const val EXTRA_OPEN_AGENT_LIST = "automation_open_agent_list"

    fun fromIntent(intent: Intent?, automationExtrasEnabled: Boolean): AgentListLaunchTarget? {
        val requested = intent?.getBooleanExtra(EXTRA_OPEN_AGENT_LIST, false) == true
        return if (requested && automationExtrasEnabled) AgentListLaunchTarget else null
    }
}
