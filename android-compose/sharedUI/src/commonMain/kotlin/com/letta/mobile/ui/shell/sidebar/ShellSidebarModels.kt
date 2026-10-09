package com.letta.mobile.ui.shell.sidebar

import androidx.compose.runtime.Immutable
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode

/** The agent panel's Active / Archived / All scope; it applies to conversations and canvases alike. */
enum class ShellArchiveFilter(val label: String) {
    Active("Active"),
    Archived("Archived"),
    All("All"),
    ;

    /** Whether an item that is [archived] shows under this filter. */
    fun admits(archived: Boolean): Boolean = when (this) {
        Active -> !archived
        Archived -> archived
        All -> true
    }
}

/** The agent the panel is about: its name and the identity its hero seat draws. */
@Immutable
data class ShellPanelAgent(
    val name: String,
    /** Gradient orb style for an agent without a mascot. */
    val orbIndex: Int = 0,
    val agentId: String? = null,
    /** With an identity the hero seat is the live mascot (large); without one it is the small orb. */
    val identity: MascotIdentity? = null,
)

/** One conversation row, already resolved for display (title, preview and time label are final text). */
@Immutable
data class ShellConversationRowModel(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val selected: Boolean = false,
    val thinking: Boolean = false,
    val deleting: Boolean = false,
    val archived: Boolean = false,
    /** letta-mobile-bzvro.17: pinned rows lead the list and show a pin. */
    val pinned: Boolean = false,
)

/** One canvas row in the panel's library. */
@Immutable
data class ShellCanvasRowModel(
    val id: CanvasId,
    val title: String,
    val timeLabel: String,
    val selected: Boolean = false,
    val archived: Boolean = false,
)

/**
 * Everything the agent panel (desktop sidebar, phone drawer) draws: the agent header, the per-agent
 * sections, the pinned conversations under an archive filter, and the canvases.
 */
@Immutable
data class ShellAgentPanelState(
    val agent: ShellPanelAgent,
    val mode: WorkPlayMode = WorkPlayMode.Work,
    /** The fleet Home page is showing: the header reads "Home" and the agent menu goes away. */
    val home: Boolean = false,
    /** The section page that is open, drawn selected. */
    val selectedSection: LensDestination? = null,
    /** Sections the host has no page for yet; their rows are left out. */
    val hiddenSections: Set<LensDestination> = emptySet(),
    /** Whether the panel ends with a Settings row, and whether Settings is the open page. */
    val showSettings: Boolean = true,
    val settingsSelected: Boolean = false,
    val archiveFilter: ShellArchiveFilter = ShellArchiveFilter.Active,
    val conversations: List<ShellConversationRowModel> = emptyList(),
    val canvases: List<ShellCanvasRowModel> = emptyList(),
)

/**
 * What the agent panel asks its host to do. Every callback defaults to a no-op, except
 * [onArchiveCanvas]: null means the host keeps no canvas archive, so canvas rows offer none.
 */
data class ShellAgentPanelActions(
    val onOpenSection: (LensDestination) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onEditAgent: () -> Unit = {},
    val onArchiveFilterChange: (ShellArchiveFilter) -> Unit = {},
    val onConversationSelected: (String) -> Unit = {},
    val onArchiveConversation: (id: String, archived: Boolean) -> Unit = { _, _ -> },
    val onDeleteConversation: (String) -> Unit = {},
    /**
     * True where the backend has no delete command, so [onDeleteConversation] archives: the
     * confirm dialog then says so instead of promising a permanent removal.
     */
    val deleteArchivesConversation: Boolean = false,
    /** letta-mobile-bzvro.17: rename a conversation; null means the host cannot, so rows offer no rename. */
    val onRenameConversation: ((id: String, title: String) -> Unit)? = null,
    /** letta-mobile-bzvro.17: pin or unpin a conversation; null means the host keeps no pins. */
    val onPinConversation: ((id: String, pinned: Boolean) -> Unit)? = null,
    val onOpenCanvas: (CanvasId) -> Unit = {},
    val onArchiveCanvas: ((id: CanvasId, archived: Boolean) -> Unit)? = null,
)
