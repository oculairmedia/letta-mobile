package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.shell.ShellOverlays
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.palette.CommandPalettePane
import com.letta.mobile.ui.shell.palette.NewConversationActions
import com.letta.mobile.ui.shell.palette.NewConversationDirectory
import com.letta.mobile.ui.shell.palette.NewConversationPicker
import com.letta.mobile.ui.shell.palette.newConversationAgents
import com.letta.mobile.desktop.chat.DesktopModelControlHost
import com.letta.mobile.desktop.chat.DesktopModelControlOverlays
import com.letta.mobile.desktop.chat.DesktopModelSurfaces
import com.letta.mobile.ui.components.ImageDropOverlay
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest

/** Agents shown in the New conversation picker's "Recent" section. */
internal const val NEW_CONVERSATION_RECENTS_LIMIT = 8

/** The model surfaces' flags are the shared overlay stack's (letta-mobile-ckmub.17.3). */
private class SharedModelSurfaces(overlays: ShellOverlays) : DesktopModelSurfaces {
    override var modelPicker by overlays::modelPicker
    override var modelsEditor by overlays::modelsEditor
}

@Immutable
internal data class DesktopOverlayData(
    val composerModelLabel: String,
    /** The window's unified search; the command palette is its picker mode. */
    val searchSources: UnifiedSearchSources,
    val railAgents: List<Pair<String, String>>,
    val rosterAgents: List<Agent>,
    val avatarStyleByAgentId: Map<String, Int>,
    val isDragActive: Boolean,
)

@Immutable
internal data class DesktopOverlayActions(
    /** The model picker's and Models sheet's host (letta-mobile-w4q4p.6.1). */
    val modelControl: DesktopModelControlHost,
    val onOpenAgent: (String) -> Unit,
    val onNavigate: (DesktopDestination) -> Unit,
    /** Opens the shared agent editor's new-agent flow (letta-mobile-ckmub.3.24). */
    val onNewAgent: () -> Unit,
    val onIrohIdentityReset: () -> Unit,
    val onNewCanvas: () -> Unit = {},
    /** Opens a command-palette pick (the unified search's hit). */
    val onOpenSearchHit: (UnifiedSearchHit) -> Unit = {},
)

/**
 * The app-level overlay stack: model picker, New Conversation directory,
 * command palette, drag-drop hint and destructive-action confirmations. Render order is z-order (later draws on top).
 * A new agent is the shared agent editor beside the chat, not an overlay.
 */
@Composable
internal fun DesktopAppOverlays(
    visibility: ShellOverlays,
    data: DesktopOverlayData,
    actions: DesktopOverlayActions,
) {
    DesktopModelControlOverlays(
        surfaces = remember(visibility) { SharedModelSurfaces(visibility) },
        host = actions.modelControl,
        selectedValue = data.composerModelLabel,
        onOpenProviders = { actions.onNavigate(DesktopDestination.Providers) },
    )
    if (visibility.newConversation) {
        val agents = remember(data.railAgents, data.rosterAgents, data.avatarStyleByAgentId) {
            newConversationAgents(data.railAgents, data.rosterAgents, data.avatarStyleByAgentId)
        }
        // The picker closes itself (onDismiss) before it runs any of these.
        val close = { visibility.newConversation = false }
        val pickerActions = NewConversationActions(actions.onOpenAgent, actions.onNewAgent, close, actions.onNewCanvas)
        NewConversationPicker(NewConversationDirectory(agents, NEW_CONVERSATION_RECENTS_LIMIT), pickerActions)
    }
    if (visibility.commandPalette) {
        CommandPalettePane(
            sources = data.searchSources,
            onOpen = actions.onOpenSearchHit,
            onDismiss = { visibility.commandPalette = false },
        )
    }
    if (data.isDragActive) {
        ImageDropOverlay()
    }
    if (visibility.irohResetConfirm) {
        LettaConfirmPane(
            request = LettaConfirmRequest(
                title = "Reset Iroh identity?",
                message = "This mints a new NodeId and breaks existing device pairings until you re-pair.",
                confirmLabel = "Reset identity",
            ),
            onConfirm = {
                visibility.irohResetConfirm = false
                actions.onIrohIdentityReset()
            },
            onDismiss = { visibility.irohResetConfirm = false },
        )
    }
}
