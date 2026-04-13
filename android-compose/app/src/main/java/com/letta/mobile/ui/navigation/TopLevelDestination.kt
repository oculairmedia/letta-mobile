package com.letta.mobile.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.ui.icons.LettaIcons

enum class TopLevelDestination(
    val icon: ImageVector,
    val label: String,
    val route: Any,
) {
    HOME(
        icon = LettaIcons.Dashboard,
        label = "Home",
        route = HomeRoute,
    ),
    CONVERSATIONS(
        icon = LettaIcons.Chat,
        label = "Chats",
        route = ConversationsRoute,
    ),
    AGENTS(
        icon = LettaIcons.Agent,
        label = "Agents",
        route = AgentListRoute,
    ),
    TOOLS(
        icon = LettaIcons.Tool,
        label = "Tools",
        route = AllToolsRoute,
    ),
    SETTINGS(
        icon = LettaIcons.Settings,
        label = "Settings",
        route = ConfigRoute,
    ),
}
