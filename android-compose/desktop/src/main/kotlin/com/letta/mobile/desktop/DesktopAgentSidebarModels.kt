package com.letta.mobile.desktop

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopConversationSummary

@Immutable
internal data class DesktopAgentSidebarState(
    val agentName: String,
    val agentOrbIndex: Int,
    val conversations: List<DesktopConversationSummary>,
    val selectedConversationId: String?,
    val thinkingConversationId: String?,
    val deletingConversationIds: Set<String> = emptySet(),
    val archiveFilter: ConversationArchiveFilter,
    val selectedDestination: DesktopDestination,
    val mode: WorkPlayMode,
)

internal data class DesktopAgentSidebarActions(
    val onArchiveFilterChange: (ConversationArchiveFilter) -> Unit,
    val onArchiveConversation: (id: String, archived: Boolean) -> Unit,
    val onModeChange: (WorkPlayMode) -> Unit,
    val onDestinationSelected: (DesktopDestination) -> Unit,
    val onConversationSelected: (String) -> Unit,
    val onDeleteConversation: (String) -> Unit,
    val onNewChat: () -> Unit,
    val onEditAgent: () -> Unit,
)

@Immutable
internal data class SidebarConversationRowModel(
    val title: String,
    val preview: String,
    val timeLabel: String,
    val selected: Boolean,
    val thinking: Boolean,
    val deleting: Boolean,
    val archived: Boolean,
)

internal data class SidebarConversationRowActions(
    val onClick: () -> Unit,
    val onArchiveToggle: () -> Unit,
    val onDelete: () -> Unit,
)

@Immutable
internal data class SidebarConversationLeadingIconModel(
    val deleting: Boolean,
    val hovered: Boolean,
    val archived: Boolean,
    val thinking: Boolean,
    val iconColor: Color,
)

@Immutable
internal data class DesktopNavRowModel(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val subdued: Boolean = false,
    val tooltip: String? = null,
) {
    val resolvedTooltip: String get() = tooltip ?: label
}

@Immutable
internal data class ConfirmDialogRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
)
