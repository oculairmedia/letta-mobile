package com.letta.mobile.ui.screens.agenteditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.letta.mobile.data.agenteditor.AgentCreated
import com.letta.mobile.data.agenteditor.AgentEditorLinks
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.agenteditor.AgentEditorPageOptions
import com.letta.mobile.ui.agenteditor.AgentEditorPanels
import com.letta.mobile.ui.dreaming.DreamingPanelHost
import com.letta.mobile.ui.shell.AgentEditorBinding
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.shellAgentEditorPage

/** Where the editor's actions lead on Android. */
class AgentEditorNavigation(
    val onBack: () -> Unit,
    /** A new agent was created: open its chat (the editor leaves the back stack). */
    val onCreated: (AgentCreated) -> Unit,
    val onDeleted: () -> Unit,
    val onOpen: (ShellDestination) -> Unit,
)

/**
 * letta-mobile-ckmub.3.24 (hard cut S15): the AgentEditor route's container. It draws the shared
 * agent editor through ShellPage and binds it to [AgentEditorViewModel] and the nav host.
 * Platform-UI-Reason: container only (Hilt view model + navigation for the shared AgentEditorPage).
 */
@Composable
fun AgentEditorScreen(navigation: AgentEditorNavigation, viewModel: AgentEditorViewModel = hiltViewModel()) {
    val agentId = viewModel.agentId
    val deps = remember(viewModel, navigation) {
        ShellPageDeps(agentEditor = shellAgentEditorPage(AgentEditorBinding(controller = { viewModel.controller }, options = { _, _ -> options(viewModel, navigation) })))
    }
    ShellPage(destination = ShellDestination.AgentEditor(agentId), deps = deps)
}

private fun options(viewModel: AgentEditorViewModel, navigation: AgentEditorNavigation): AgentEditorPageOptions {
    val agentId = viewModel.agentId
    val dreaming = viewModel.dreaming
    return AgentEditorPageOptions(
        onBack = navigation.onBack,
        onSaved = { created -> if (agentId == null) navigation.onCreated(created) else navigation.onBack() },
        onDeleted = navigation.onDeleted,
        links = agentId?.let { id ->
            AgentEditorLinks(
                memory = { navigation.onOpen(ShellDestination.Memory(id)) },
                schedules = { navigation.onOpen(ShellDestination.Schedules(id)) },
                channels = { navigation.onOpen(ShellDestination.Channels) },
            )
        } ?: AgentEditorLinks(),
        panels = AgentEditorPanels(dreaming = dreaming?.let { { DreamingPanelHost(controller = it.controller, target = it.target) } }),
        settings = viewModel.settings,
    )
}
