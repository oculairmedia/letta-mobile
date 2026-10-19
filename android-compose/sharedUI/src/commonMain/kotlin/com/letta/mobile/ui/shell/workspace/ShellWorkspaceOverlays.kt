package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.shell.ShellOverlays
import com.letta.mobile.ui.components.ImageDropOverlay
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.palette.CommandPalettePane
import com.letta.mobile.ui.shell.palette.NewConversationActions
import com.letta.mobile.ui.shell.palette.NewConversationDirectory
import com.letta.mobile.ui.shell.palette.NewConversationPicker
import com.letta.mobile.ui.shell.palette.newConversationAgents
import com.letta.mobile.ui.shell.shortcuts.ShortcutHelpOverlay

/** What the workspace's overlays draw from. */
@Immutable
data class ShellWorkspaceOverlayData(
    /** The workspace's unified search; the command palette is its picker mode. */
    val searchSources: UnifiedSearchSources,
    /** The rail's agents as (id, name), then the whole roster, for the New conversation picker. */
    val railAgents: List<Pair<String, String>>,
    val rosterAgents: List<Agent>,
    val avatarStyleByAgentId: Map<String, Int> = emptyMap(),
    /** An image is being dragged over the window. */
    val isDragActive: Boolean = false,
)

/** What the overlays' picks do. */
class ShellWorkspaceOverlayActions(
    val onOpenAgent: (String) -> Unit,
    /** The shared agent editor's new-agent flow. */
    val onNewAgent: () -> Unit,
    /** A command-palette pick (the unified search's hit). */
    val onOpenSearchHit: (UnifiedSearchHit) -> Unit,
    val onNewCanvas: (() -> Unit)? = null,
)

/** Agents shown in the New conversation picker's "Recent" section. */
const val NEW_CONVERSATION_RECENTS_LIMIT = 8

/**
 * The workspace's overlay stack, moved from desktop's app overlays: the New
 * conversation directory, the command palette and the image drag hint. Render order is z-order.
 * A host draws its own overlays (desktop's model picker, its identity reset) beside these.
 */
@Composable
fun ShellWorkspaceOverlays(visibility: ShellOverlays, data: ShellWorkspaceOverlayData, actions: ShellWorkspaceOverlayActions) {
    if (visibility.shortcutHelp) ShortcutHelpOverlay(onDismiss = { visibility.shortcutHelp = false })
    if (visibility.newConversation) NewConversationOverlay(visibility, data, actions)
    if (visibility.commandPalette) {
        CommandPalettePane(
            sources = data.searchSources,
            onOpen = actions.onOpenSearchHit,
            onDismiss = { visibility.commandPalette = false },
        )
    }
    if (data.isDragActive) ImageDropOverlay()
}

@Composable
private fun NewConversationOverlay(visibility: ShellOverlays, data: ShellWorkspaceOverlayData, actions: ShellWorkspaceOverlayActions) {
    val agents = remember(data.railAgents, data.rosterAgents, data.avatarStyleByAgentId) {
        newConversationAgents(data.railAgents, data.rosterAgents, data.avatarStyleByAgentId)
    }
    // The picker closes itself (onDismiss) before it runs any of these.
    val close = { visibility.newConversation = false }
    NewConversationPicker(
        NewConversationDirectory(agents, NEW_CONVERSATION_RECENTS_LIMIT),
        NewConversationActions(actions.onOpenAgent, actions.onNewAgent, close, actions.onNewCanvas),
    )
}
