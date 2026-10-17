package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.CompactAgentProfile
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.ui.chat.session.ChatModelOption
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/**
 * letta-mobile-ckmub.3.24 (hard cut S15): the agent editor of every client, for an existing agent
 * ([AgentEditorMode.Edit]) or a new one ([AgentEditorMode.Create]). It lives as long as [scope] (the
 * Android view model's, the desktop pane's composition) and writes only what changed.
 */
class AgentEditorController(
    private val backend: AgentEditorBackend,
    private val scope: CoroutineScope,
    mode: AgentEditorMode,
) : AgentEditorActions {
    private val _state = MutableStateFlow(AgentEditorState(mode = mode, loading = mode is AgentEditorMode.Edit))
    val state: StateFlow<AgentEditorState> = _state.asStateFlow()

    /** The agent a new one starts from, once picked and loaded. */
    private var starterAgent: Agent? = null

    init {
        combine(backend.agents, backend.models, backend.tools) { agents, models, tools ->
            Catalogs(agents, models, tools)
        }.onEach { catalogs -> _state.update { catalogs.applyTo(it) } }.launchIn(scope)
        backend.modelsUnavailable.onEach { reason -> _state.update { it.copy(modelsUnavailable = reason) } }.launchIn(scope)
        scope.launch { backend.refreshCatalogs() }
        load()
    }

    override fun update(change: AgentEditorForm.() -> AgentEditorForm) {
        _state.update { it.copy(form = it.form.change(), error = null) }
    }

    override fun pickStarter(starter: AgentStarter) {
        _state.update { it.copy(starter = starter) }
        val copyOf = starter as? AgentStarter.CopyOf
        if (copyOf == null) {
            starterAgent = null
            return
        }
        scope.launch {
            guarded("Could not load ${copyOf.name}") {
                val source = loadAgent(copyOf.agentId)
                starterAgent = source
                _state.update { it.copy(form = AgentEditorParams.starterFormOf(source, it.form)) }
            }
        }
    }

    override fun save(onSaved: (AgentCreated) -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            guarded(if (current.creating) "Could not create the agent" else "Could not save the agent") {
                val result = when (val mode = current.mode) {
                    AgentEditorMode.Create -> backend.create(
                        AgentEditorParams.createOf(
                            current.form.copy(compactProfile = current.compactProfile),
                            starterAgent,
                            backend.models.value,
                        ),
                    )
                    is AgentEditorMode.Edit -> saveEdits(mode.agentId, current)
                }
                _state.update { it.copy(original = it.form) }
                onSaved(result)
            }
            _state.update { it.copy(saving = false) }
        }
    }

    override fun delete(onDeleted: () -> Unit) {
        val agentId = _state.value.agentId ?: return
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            guarded("Could not delete the agent") {
                backend.delete(AgentId(agentId))
                onDeleted()
            }
            _state.update { it.copy(saving = false) }
        }
    }

    override fun retry() = load()

    override fun dismissError() = _state.update { it.copy(error = null) }

    override suspend fun writeMetadata(metadata: Map<String, JsonElement>) {
        val agentId = _state.value.agentId ?: return
        backend.update(AgentId(agentId), AgentUpdateParams(metadata = metadata))
    }

    private suspend fun saveEdits(agentId: String, current: AgentEditorState): AgentCreated {
        val id = AgentId(agentId)
        val original = current.original ?: current.form
        AgentEditorParams.updateOf(original, current.form, current.agent)?.let { backend.update(id, it) }
        val (attach, detach) = AgentEditorParams.toolChanges(original, current.form)
        attach.forEach { backend.attachTool(id, ToolId(it)) }
        detach.forEach { backend.detachTool(id, ToolId(it)) }
        return AgentCreated(agentId)
    }

    /** The agent, or a failure once [LOAD_TIMEOUT_MS] passes: a load that never answers must not spin forever. */
    private suspend fun loadAgent(agentId: String): Agent =
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { backend.load(AgentId(agentId)) } ?: error(LOAD_TIMED_OUT)

    private fun load() {
        val agentId = _state.value.agentId ?: return
        _state.update { it.copy(loading = true, loadError = null) }
        scope.launch {
            try {
                val agent = loadAgent(agentId)
                val form = AgentEditorParams.formOf(agent)
                _state.update { it.copy(loading = false, agent = agent, form = form, original = form) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.message ?: "Could not load the agent") }
            }
        }
    }

    /** Runs [block], reporting a failure as the page's error in [fallback]'s words when it has none. */
    private suspend fun guarded(fallback: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message?.takeIf(String::isNotBlank) ?: fallback) }
        }
    }

    /** The roster, models and tools, as the page offers them. */
    private inner class Catalogs(val agents: List<Agent>, val models: List<LlmModel>, val tools: List<Tool>) {
        fun applyTo(state: AgentEditorState): AgentEditorState = state.copy(
            starters = agents.map { AgentStarter.CopyOf(it.id.value, it.name) },
            models = models.map(::optionOf),
            tools = tools.map(::toolOf),
            compactModels = models.filter(CompactAgentProfile::isSmallModel).map { ModelCatalog.selectionValue(models, it) }.toSet(),
        )

        private fun optionOf(model: LlmModel): ChatModelOption {
            // The catalog's selection value: unique even where two routes serve the same handle.
            val handle = ModelCatalog.selectionValue(models, model)
            return ChatModelOption(
                handle = handle,
                label = model.displayName,
                provider = model.providerName ?: model.providerType.ifBlank { null },
                reasoningEfforts = backend.reasoningEffortsFor(handle).toImmutableList(),
            )
        }

        private fun toolOf(tool: Tool) = AgentToolOption(
            id = tool.id.value,
            name = tool.name,
            description = tool.description,
            mcp = tool.toolType?.contains("mcp", ignoreCase = true) == true,
        )
    }

    companion object {
        /** How long the editor waits for an agent before it says so and offers a retry. */
        const val LOAD_TIMEOUT_MS = 20_000L
        const val LOAD_TIMED_OUT = "The agent did not load. Check the connection and try again."
    }
}
