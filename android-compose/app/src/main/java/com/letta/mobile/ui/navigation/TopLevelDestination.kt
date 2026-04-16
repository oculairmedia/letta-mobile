package com.letta.mobile.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.ui.icons.LettaIcons

enum class TopLevelDestination(
    val icon: ImageVector,
    val label: String,
    val route: Any,
) {
    HOME(
        icon = LettaIcons.Apps,
        label = "Home",
        route = HomeRoute,
    ),
    CHAT(
        icon = LettaIcons.Chat,
        label = "Chat",
        route = ConversationsRoute,
    ),
    ADMIN(
        icon = LettaIcons.Dashboard,
        label = "Admin",
        route = AdminRoute,
    ),
}
