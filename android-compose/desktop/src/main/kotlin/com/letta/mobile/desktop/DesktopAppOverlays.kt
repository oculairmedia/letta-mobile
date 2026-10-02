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
import com.letta.mobile.desktop.chat.DesktopModelPicker
import com.letta.mobile.desktop.chat.DesktopModelsEditor
import com.letta.mobile.ui.components.ImageDropOverlay
import kotlinx.coroutines.launch

/** Avatar chips shown in the New Conversation "Recent" row. */
internal const val NEW_CONVERSATION_RECENTS_LIMIT = 8

/** Mutable visibility flags for the app-level overlay stack. */
@Stable
internal class DesktopOverlayVisibility {
    var modelPicker by mutableStateOf(false)

    /** The "Models" exposure editor opened from the picker's "Edit Models…" (letta-mobile-w4q4p.6.1). */
    var modelsEditor by mutableStateOf(false)
    var commandPalette by mutableStateOf(false)
    var newConversation by mutableStateOf(false)
    var newAgent by mutableStateOf(false)
    var irohResetConfirm by mutableStateOf(false)
}

@Immutable
internal data class DesktopOverlayData(
    val availableModels: List<LlmModel>,
    /** The chat's model list as a flow: the picker's fallback when the host has no admin catalog. */
    val chatModels: kotlinx.coroutines.flow.StateFlow<List<LlmModel>>,
    /** The host's provider/model control, shared with the Providers destination. */
    val modelControl: com.letta.mobile.data.repository.modelcontrol.ModelControlSession?,
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
    /** Re-reads the chat's model list after the host catalog changed. */
    val reloadChatModels: suspend () -> Unit = {},
    val onSelectConversation: (String) -> Unit,
    val onOpenAgent: (String) -> Unit,
    val onNavigate: (DesktopDestination) -> Unit,
    val onCreateAgent: (name: String, modelValue: String?) -> Unit,
    val onIrohIdentityReset: () -> Unit,
    val onNewCanvas: () -> Unit = {},
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
        DesktopModelPicker(
            session = data.modelControl,
            chatModels = data.chatModels,
            selectedValue = data.composerModelLabel,
            reloadChatModels = actions.reloadChatModels,
            onSelect = actions.onModelSelected,
            onEditModels = {
                visibility.modelPicker = false
                visibility.modelsEditor = true
            },
            onDismiss = { visibility.modelPicker = false },
        )
    }
    val modelControl = data.modelControl
    if (visibility.modelsEditor && modelControl != null) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        DesktopModelsEditor(
            session = modelControl,
            onAddProvider = {
                visibility.modelsEditor = false
                actions.onNavigate(DesktopDestination.Providers)
            },
            onDismiss = {
                visibility.modelsEditor = false
                // What the picker shows may have changed; the chat routes picks through its own list.
                scope.launch { runCatching { actions.reloadChatModels() } }
            },
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
                onNewCanvas = {
                    visibility.newConversation = false
                    actions.onNewCanvas()
                },
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
        ImageDropOverlay()
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
