package com.letta.mobile.ui.agenteditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.agenteditor.AgentEditorActions
import com.letta.mobile.data.agenteditor.AgentEditorState
import com.letta.mobile.data.agenteditor.AgentToolOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.surface.composer.ComposerModelPickerSheet
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickMode
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerEmpty
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.components.picker.PickerSlots
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.mascot.MascotPicker
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.toImmutableList

/** What a pane reads and writes. */
internal class AgentEditorPaneInputs(
    val state: AgentEditorState,
    val actions: AgentEditorActions,
    val options: AgentEditorPageOptions,
    val identity: AgentEditorIdentity,
)

/** The open [pane], in the host's container for it; [onClose] closes it. */
@Composable
internal fun AgentEditorPaneHost(pane: AgentEditorPane, inputs: AgentEditorPaneInputs, onClose: () -> Unit) {
    when (pane) {
        AgentEditorPane.Mascot -> MascotPane(inputs.identity, onClose)
        AgentEditorPane.Model -> ModelPane(inputs, onClose)
        AgentEditorPane.Tools -> ToolsPane(inputs, onClose)
        AgentEditorPane.Dreaming -> inputs.options.panels.dreaming?.let { PanelPane("Dreaming", it, onClose) }
        AgentEditorPane.Secrets -> inputs.options.panels.secrets?.let { PanelPane("Secrets", it, onClose) }
    }
}

@Composable
private fun MascotPane(identity: AgentEditorIdentity, onClose: () -> Unit) {
    LettaPane(LettaPaneKind.Modal, onDismiss = onClose, title = "Mascot", testTag = AgentEditorPageTags.MASCOT_PANE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xl)) {
            MascotPicker(identity = identity.current, onChange = identity.onChange)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(vertical = LettaDimens.Space.sm)) { Text("Done") }
        }
    }
}

/** The composer's model picker (one picker for every model choice); a pick keeps the effort only where the new model takes it. */
@Composable
private fun ModelPane(inputs: AgentEditorPaneInputs, onClose: () -> Unit) {
    val state = inputs.state
    ComposerModelPickerSheet(
        model = ChatModelUiState(
            currentHandle = state.form.model.ifBlank { null },
            currentLabel = state.modelOption?.label ?: state.form.model,
            options = state.models.toImmutableList(),
            unavailableReason = state.modelsUnavailable,
        ),
        onSelect = { option ->
            inputs.actions.update { copy(model = option.handle, reasoningEffort = reasoningEffort?.takeIf { it in option.reasoningEfforts }) }
            onClose()
        },
        onDismiss = onClose,
        testTag = AgentEditorPageTags.MODEL_PANE,
    )
}

/** Every tool the agent can have, built-in and MCP apart; a pick attaches or removes it. */
@Composable
private fun ToolsPane(inputs: AgentEditorPaneInputs, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val selected = inputs.state.form.toolIds
    LettaPicker(
        spec = LettaPickerSpec(
            sections = toolSections(inputs.state.tools),
            query = query,
            mode = PickMode.Multi,
            selected = selected,
            search = PickerSearch("Search tools"),
            empty = PickerEmpty("No tools", "This server lists no tools for agents."),
        ),
        callbacks = PickerCallbacks(
            onQuery = { query = it },
            onPick = { item -> inputs.actions.update { copy(toolIds = if (item.value in toolIds) toolIds - item.value else toolIds + item.value) } },
            onDismiss = onClose,
        ),
        pane = PickerPane(title = "Tools", testTag = AgentEditorPageTags.TOOLS_PANE),
        slots = PickerSlots(footer = {
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(LettaDimens.Space.sm)) { Text("Done") }
        }),
    )
}

internal fun toolSections(tools: List<AgentToolOption>): List<PickerSection<String>> {
    val (mcp, builtIn) = tools.sortedBy { it.name.lowercase() }.partition { it.mcp }
    return listOf(PickerSection("tools", "Tools", builtIn.map(::toolItem)), PickerSection("mcp", "MCP", mcp.map(::toolItem)))
        .filter { it.items.isNotEmpty() }
}

private fun toolItem(tool: AgentToolOption) = PickerItem(key = tool.id, value = tool.id, title = tool.name, subtitle = tool.description)

@Composable
private fun PanelPane(title: String, content: @Composable () -> Unit, onClose: () -> Unit) {
    LettaPane(LettaPaneKind.Modal, onDismiss = onClose, title = title, testTag = AgentEditorPageTags.PANEL_PANE) {
        Column(Modifier.fillMaxWidth().testTag(AgentEditorPageTags.PANEL_PANE + "-" + title.lowercase())) { content() }
    }
}
