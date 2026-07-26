package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.DesktopCommandPalette
import com.letta.mobile.desktop.chat.DesktopModelPickerSheet

/** Avatar chips shown in the New Conversation "Recent" row. */
internal const val NEW_CONVERSATION_RECENTS_LIMIT = 8

/** Mutable visibility flags for the app-level overlay stack. */
@Stable
internal class DesktopOverlayVisibility {
    var modelPicker by mutableStateOf(false)
    var commandPalette by mutableStateOf(false)
    var newConversation by mutableStateOf(false)
    var newAgent by mutableStateOf(false)
    var irohResetConfirm by mutableStateOf(false)
}

@Immutable
internal data class DesktopOverlayData(
    val availableModels: List<LlmModel>,
    val composerModelLabel: String,
    val modelOptions: List<Pair<String, String>>,
    val paletteItems: List<PaletteItem>,
    val railAgents: List<Pair<String, String>>,
    val rosterAgents: List<Agent>,
    val avatarStyleByAgentId: Map<String, Int>,
    val isDragActive: Boolean,
)

@Immutable
internal data class DesktopOverlayActions(
    val onModelSelected: (String) -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onOpenAgent: (String) -> Unit,
    val onNavigate: (DesktopDestination) -> Unit,
    val onCreateAgent: (name: String, modelValue: String?) -> Unit,
    val onIrohIdentityReset: () -> Unit,
)

/**
 * The app-level overlay stack: model picker, New Conversation directory,
 * command palette, drag-drop hint, destructive-action confirmations, and the
 * new-agent dialog. Render order is z-order (later draws on top).
 */
@Composable
internal fun DesktopAppOverlays(
    visibility: DesktopOverlayVisibility,
    data: DesktopOverlayData,
    actions: DesktopOverlayActions,
) {
    if (visibility.modelPicker) {
        DesktopModelPickerSheet(
            models = data.availableModels,
            selectedValue = data.composerModelLabel,
            onSelect = actions.onModelSelected,
            onDismiss = { visibility.modelPicker = false },
        )
    }
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
                    visibility.newAgent = true
                },
                onDismiss = { visibility.newConversation = false },
            ),
        )
    }
    if (visibility.commandPalette) {
        DesktopCommandPalette(
            items = data.paletteItems,
            onSelect = { item ->
                when (item.kind) {
                    PaletteItemKind.Conversation -> actions.onSelectConversation(item.id)
                    PaletteItemKind.Agent -> actions.onOpenAgent(item.id)
                    PaletteItemKind.Destination ->
                        DesktopDestination.entries.firstOrNull { it.name == item.id }
                            ?.let(actions.onNavigate)
                }
            },
            onDismiss = { visibility.commandPalette = false },
        )
    }
    if (data.isDragActive) {
        DesktopImageDropOverlay()
    }
    if (visibility.irohResetConfirm) {
        DesktopConfirmDialog(
            request = ConfirmDialogRequest(
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
    // Edit agent is a full-page surface (DesktopEditAgentSurface), not a modal.
    if (visibility.newAgent) {
        NewAgentDialog(
            NewAgentDialogParams(
                modelOptions = data.modelOptions,
                onDismiss = { visibility.newAgent = false },
                onCreate = { name, modelValue ->
                    visibility.newAgent = false
                    actions.onCreateAgent(name, modelValue)
                },
            ),
        )
    }
}
