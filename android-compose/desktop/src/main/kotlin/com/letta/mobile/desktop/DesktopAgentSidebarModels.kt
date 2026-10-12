package com.letta.mobile.desktop

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsActions
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsState
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarkActions

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
    /** letta-mobile-7mys7: each fork's source conversation (ConversationLineage). */
    val conversationParents: Map<String, String> = emptyMap(),
    /** letta-mobile-zxk2n.5: the list's client marks (settled last, markers before titles). */
    val listMarks: DesktopSidebarListMarks = DesktopSidebarListMarks(),
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
    /** What the backend's delete really does; the confirm dialog and the row menu word and offer it accordingly. */
    val deleteBehavior: ConversationDeleteBehavior = ConversationDeleteBehavior.Permanent,
    /** letta-mobile-bzvro.17: rename (persists the conversation's summary) and pin. */
    val onRenameConversation: (id: String, title: String) -> Unit = { _, _ -> },
    val onPinConversation: (id: String, pinned: Boolean) -> Unit = { _, _ -> },
    /** letta-mobile-7mys7 (G37): fork a conversation, named or not; null when the backend cannot fork. */
    val onForkConversation: ((id: String, name: String?) -> Unit)? = null,
    val onNewChat: () -> Unit,
    val onEditAgent: () -> Unit,
    val onOpenCanvas: (com.letta.mobile.data.canvas.CanvasId) -> Unit = {},
    val onNewCanvas: () -> Unit = {},
    val onArchiveCanvas: (id: com.letta.mobile.data.canvas.CanvasId, archived: Boolean) -> Unit = { _, _ -> },
    /** letta-mobile-zxk2n.5: settle and marker; null leaves both out of the row menu. */
    val conversationMarks: ShellConversationMarkActions? = null,
    /** letta-mobile-zxk2n.5: what the Chat options dialog changes. */
    val chatOptions: ShellChatOptionsActions = ShellChatOptionsActions(),
)

/**
 * letta-mobile-zxk2n.5: the sidebar's client marks, the stack's archived chats for the Archived
 * chats dialog, and the Chat options dialog's state (null hides it).
 */
@Immutable
internal data class DesktopSidebarListMarks(
    val settledIds: Set<String> = emptySet(),
    val markers: Map<String, String> = emptyMap(),
    val archivedConversations: List<DesktopConversationSummary> = emptyList(),
    val chatOptions: ShellChatOptionsState? = null,
)
