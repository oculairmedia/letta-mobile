package com.letta.mobile

import android.content.Intent
import com.letta.mobile.ui.navigation.AgentListRoute

sealed interface AppLaunchTarget {
    fun toRoute(): Any

    companion object {
        fun fromIntent(intent: Intent?): AppLaunchTarget? {
            return NotificationNavigationTarget.fromIntent(intent)
                ?: AgentListLaunchTarget.fromIntent(intent)
        }
    }
}

data object AgentListLaunchTarget : AppLaunchTarget {
    private const val EXTRA_OPEN_AGENT_LIST = "automation_open_agent_list"

    override fun toRoute(): Any = AgentListRoute

    fun fromIntent(intent: Intent?): AgentListLaunchTarget? {
        return if (intent?.getBooleanExtra(EXTRA_OPEN_AGENT_LIST, false) == true) {
            AgentListLaunchTarget
        } else {
            null
        }
    }
}
