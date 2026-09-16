package com.letta.mobile.ui.navigation

import androidx.navigation.NavHostController
import com.letta.mobile.feature.chat.route.AgentChatRoute

internal fun NavHostController.openResidentChatOrNavigate(route: AgentChatRoute) {
    // Match the complete route so fresh requests, search targets and project context stay distinct.
    if (!popBackStack(route, inclusive = false)) navigate(route)
}
