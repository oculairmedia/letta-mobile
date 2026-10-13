package com.letta.mobile.ui.screens.agenteditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.agenteditor.AgentEditorController
import com.letta.mobile.data.agenteditor.AgentEditorMode
import com.letta.mobile.data.agenteditor.AgentEditorRepositories
import com.letta.mobile.data.agenteditor.RepositoryAgentEditorBackend
import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.dreaming.AdminRpcDreamingRepository
import com.letta.mobile.data.dreaming.DreamHistorySource
import com.letta.mobile.data.dreaming.DreamTarget
import com.letta.mobile.data.dreaming.DreamingController
import com.letta.mobile.data.memory.memfs.MemfsSource
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.screens.agentlist.AndroidAgentCreator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The repositories the editor reads and writes, injected as one. */
class AndroidAgentEditorDeps @Inject constructor(
    val agents: IAgentRepository,
    val tools: IToolRepository,
    val models: IModelRepository,
    val creator: AndroidAgentCreator,
)

/** What the agent's Dreaming panel is bound to: the Iroh host's `reflection.*`, the MemFS dream log, live runs. */
class AgentEditorDreamingSources @Inject constructor(
    val rpc: AdminRpcInvoker,
    val memfs: MemfsSource,
    val subagents: ISubagentRepository,
)

/**
 * letta-mobile-ckmub.3.24 (hard cut S15): Android's binding of the shared agent editor. It only holds
 * the shared [AgentEditorController] (and the agent's [DreamingController]) for as long as the route
 * lives; the page and the logic are shared. A route without an agent is the new-agent flow.
 */
@HiltViewModel
class AgentEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deps: AndroidAgentEditorDeps,
    dreamingSources: AgentEditorDreamingSources,
    val settings: SecureSettingsStore,
) : ViewModel() {
    val agentId: String? = savedStateHandle.get<String>("agentId")?.takeIf { it.isNotBlank() }

    val controller = AgentEditorController(
        backend = RepositoryAgentEditorBackend(AgentEditorRepositories(deps.agents, deps.tools, deps.models), deps.creator),
        scope = viewModelScope,
        mode = AgentEditorMode.of(agentId),
    )

    /** The agent's Dreaming panel; a new agent has none yet. */
    val dreaming: AgentDreaming? = agentId?.let { id ->
        AgentDreaming(
            target = DreamTarget(AgentId(id)),
            controller = DreamingController(
                repository = AdminRpcDreamingRepository(dreamingSources.rpc),
                history = DreamHistorySource(dreamingSources.memfs),
                scope = viewModelScope,
                activity = { agent ->
                    dreamingSources.subagents.activeSubagentsFlow(SubagentParentScope(agent.value, ConversationCompactRpc.DEFAULT_CONVERSATION))
                },
            ),
        )
    }

    override fun onCleared() {
        dreaming?.controller?.close()
    }
}

/** An agent's Dreaming controller and the agent it is for. */
class AgentDreaming(val target: DreamTarget, val controller: DreamingController)
