package com.letta.mobile.desktop

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopConversationSummary

@Immutable
internal data class DesktopAgentSidebarState(
    val agentName: String,
    val agentOrbIndex: Int,
    /** The selected agent and its mascot identity: the header draws the live mascot when both are known. */
    val agentId: String? = null,
    val agentIdentity: com.letta.mobile.avatar.core.MascotIdentity? = null,
    val conversations: List<DesktopConversationSummary>,
    val selectedConversationId: String?,
    val thinkingConversationId: String?,
    val deletingConversationIds: Set<String> = emptySet(),
    /** letta-mobile-bzvro.17: pinned conversations, listed first. */
    val pinnedConversationIds: Set<String> = emptySet(),
    val archiveFilter: ConversationArchiveFilter,
    val selectedDestination: DesktopDestination,
    val mode: WorkPlayMode,
    /** Every canvas, newest first; canvases are shared so this is not scoped to the agent. */
    val canvases: List<com.letta.mobile.data.canvas.CanvasDocument> = emptyList(),
    val activeCanvasId: com.letta.mobile.data.canvas.CanvasId? = null,
    /** Which of [canvases] are archived, so each row offers archive or restore. */
    val archivedCanvasIds: Set<com.letta.mobile.data.canvas.CanvasId> = emptySet(),
)

internal data class DesktopAgentSidebarActions(
    val onArchiveFilterChange: (ConversationArchiveFilter) -> Unit,
    val onArchiveConversation: (id: String, archived: Boolean) -> Unit,
    val onModeChange: (WorkPlayMode) -> Unit,
    val onDestinationSelected: (DesktopDestination) -> Unit,
    val onConversationSelected: (String) -> Unit,
    val onDeleteConversation: (String) -> Unit,
    /** The backend's delete only archives (no delete command), so the confirm dialog says archive. */
    val deleteArchivesConversation: Boolean = false,
    /** letta-mobile-bzvro.17: rename (persists the conversation's summary) and pin. */
    val onRenameConversation: (id: String, title: String) -> Unit = { _, _ -> },
    val onPinConversation: (id: String, pinned: Boolean) -> Unit = { _, _ -> },
    val onNewChat: () -> Unit,
    val onEditAgent: () -> Unit,
    val onOpenCanvas: (com.letta.mobile.data.canvas.CanvasId) -> Unit = {},
    val onNewCanvas: () -> Unit = {},
    val onArchiveCanvas: (id: com.letta.mobile.data.canvas.CanvasId, archived: Boolean) -> Unit = { _, _ -> },
)

@Immutable
internal data class ConfirmDialogRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
)
