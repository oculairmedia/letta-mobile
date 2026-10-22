package com.letta.mobile.ui.screens.agenteditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.dreaming.AdminRpcDreamingRepository
import com.letta.mobile.data.dreaming.DreamHistorySource
import com.letta.mobile.data.dreaming.DreamTarget
import com.letta.mobile.data.dreaming.DreamingController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.ui.agenteditor.AgentEditorPanels
import com.letta.mobile.ui.dreaming.DreamingPanelHost
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.feature.chat.screen.SharedNavDrawerViewModel
import com.letta.mobile.ui.shell.pages.ShellAgentEditorHost

/** letta-mobile-ckmub.3.24, ckmub.2: what Android adds to the shared agent editor: its creator and its Dreaming panel. */
@Composable
internal fun androidAgentEditorHost(drawer: SharedNavDrawerViewModel): ShellAgentEditorHost {
    val sources = drawer.sources
    val graph by sources.sessions.currentGraph.collectAsStateWithLifecycle()
    val registry = LocalMascotRegistry.current
    return ShellAgentEditorHost(
        creator = sources.creator,
        modelCatalog = graph.modelRepository.llmModels,
        panels = { agent -> AgentEditorPanels(dreaming = { AndroidDreamingPanel(drawer, agent) }) },
        // The editor's mascot pick shows at once, before the roster next refreshes.
        onIdentityChange = { agent, identity -> registry.update(registry.identities + (agent.value to identity)) },
        onRosterChanged = { drawer.refresh(agent = null, roster = true) },
    )
}

/** The agent's Dreaming panel over the Iroh host's `reflection.*`, its MemFS dream log and its live runs. */
@Composable
private fun AndroidDreamingPanel(drawer: SharedNavDrawerViewModel, agent: AgentId) {
    val scope = rememberCoroutineScope()
    val sources = drawer.sources
    val graph by sources.sessions.currentGraph.collectAsStateWithLifecycle()
    val controller = remember(agent, scope, graph) {
        DreamingController(
            repository = AdminRpcDreamingRepository(sources.dreamingRpc),
            history = DreamHistorySource(sources.library.memfs),
            scope = scope,
            activity = { id ->
                graph.subagentRepository.activeSubagentsFlow(SubagentParentScope(id.value, ConversationCompactRpc.DEFAULT_CONVERSATION))
            },
        )
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    DreamingPanelHost(controller = controller, target = remember(agent) { DreamTarget(agent) })
}
