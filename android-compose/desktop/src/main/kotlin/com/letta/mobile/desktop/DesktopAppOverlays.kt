package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.palette.CommandPalettePane
import com.letta.mobile.desktop.chat.DesktopModelControlHost
import com.letta.mobile.desktop.chat.DesktopModelControlOverlays
import com.letta.mobile.desktop.chat.DesktopModelSurfaces
import com.letta.mobile.ui.components.ImageDropOverlay
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest

/** Avatar chips shown in the New Conversation "Recent" row. */
internal const val NEW_CONVERSATION_RECENTS_LIMIT = 8

/** Mutable visibility flags for the app-level overlay stack. */
@Stable
internal class DesktopOverlayVisibility : DesktopModelSurfaces {
    override var modelPicker by mutableStateOf(false)

    /** The "Models" exposure editor opened from the picker's "Edit Models…" (letta-mobile-w4q4p.6.1). */
    override var modelsEditor by mutableStateOf(false)
    var commandPalette by mutableStateOf(false)
    var newConversation by mutableStateOf(false)
    var irohResetConfirm by mutableStateOf(false)

    /** letta-mobile-zxk2n.5: `/rename`'s dialog for the open chat. */
    var renameChat by mutableStateOf(false)
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
    visibility: DesktopOverlayVisibility,
    data: DesktopOverlayData,
    actions: DesktopOverlayActions,
) {
    DesktopModelControlOverlays(
        surfaces = visibility,
        host = actions.modelControl,
        selectedValue = data.composerModelLabel,
        onOpenProviders = { actions.onNavigate(DesktopDestination.Providers) },
    )
    if (visibility.newConversation) {
        val directoryRows = remember(data.railAgents, data.rosterAgents, data.avatarStyleByAgentId) {
            buildNewConversationRows(data.railAgents, data.rosterAgents, data.avatarStyleByAgentId)
        }
        DesktopNewConversationSurface(
            recents = directoryRows.take(NEW_CONVERSATION_RECENTS_LIMIT),
            directory = directoryRows,
            actions = DesktopNewConversationActions(
                onAgentSelected = {
                    visibility.newConversation = false
                    actions.onOpenAgent(it)
                },
                onCreateNewAgent = {
                    visibility.newConversation = false
                    actions.onNewAgent()
                },
                onDismiss = { visibility.newConversation = false },
                onNewCanvas = {
                    visibility.newConversation = false
                    actions.onNewCanvas()
                },
            ),
        )
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
