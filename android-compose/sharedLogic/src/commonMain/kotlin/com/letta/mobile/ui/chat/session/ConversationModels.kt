package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ConversationModelSelections
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The conversation the model chip describes, and the label it shows. */
data class ConversationModelFocus(
    val conversationId: ConversationId? = null,
    /** The conversation's agent, whose route a create's model may name. */
    val agentId: AgentId? = null,
    val label: String = ConversationModels.DEFAULT_LABEL,
)

/**
 * A change to a conversation's chip. [label] applies only while that conversation is still the
 * focused one (null keeps the chip's label); [error] is shown either way.
 */
data class ConversationModelUpdate(
    val conversationId: ConversationId,
    val label: String? = null,
    val error: String? = null,
)

/** What [ConversationModels] reads and writes on the chat host that owns it. */
interface ConversationModelHost {
    /** Where the models are listed and switched; null while there is no connection. */
    val backend: ConversationModelBackend?
    val isClosed: Boolean
    val focus: StateFlow<ConversationModelFocus>

    fun showModel(update: ConversationModelUpdate)

    /** A confirmed switch, for the host's recently used models. */
    suspend fun recordModel(model: String) = Unit
}

/**
 * letta-mobile-by4vm.98.1, ckmub.17.2 (moved from desktop's DesktopConversationModels): the chat's
 * models in one place: the backend's model list, each conversation's pick and effort, the
 * per-conversation switch, and the composer chip ([ChatModelSource]) with the reasoning efforts the
 * host catalog advertises. Desktop's chat controller and the web's chat page bind it.
 */
class ConversationModels(
    private val scope: CoroutineScope,
    private val host: ConversationModelHost,
    private val agentByIdProvider: suspend (agentIds: Set<String>) -> Map<String, Agent> = { emptyMap() },
) : ChatModelSource {
    private val loaded = MutableStateFlow<List<LlmModel>>(emptyList())
    private val catalog = MutableStateFlow<ModelCatalogRepository?>(null)
    private var catalogFollow: Job? = null

    /**
     * Per-conversation model overrides: the host's (seeded from each conversation list, dxz5s) and
     * this session's picks. The effective model otherwise comes from the conversation's agent.
     */
    val picks = ConversationModelSelections()

    val catalogLoader = ChatModelCatalogLoader(
        scope = scope,
        sources = ChatModelCatalogSources(
            agentByIdProvider = agentByIdProvider,
            onModelsLoaded = { if (!host.isClosed) loaded.value = it },
            selectedAgent = { host.focus.value.agentId },
        ),
    )

    /** The active backend's models. */
    val available: StateFlow<List<LlmModel>> = loaded.asStateFlow()

    /** Each conversation's own model and window; the context chip and card size their window from it. */
    val selections: StateFlow<ConversationModelChoices> get() = picks.choices

    /** True when the active backend can send a reasoning effort with a switch (the effort chip). */
    val supportsReasoningEffort: Boolean
        get() = host.backend?.supportsReasoningEffort == true

    @OptIn(ExperimentalCoroutinesApi::class)
    override val model: StateFlow<ChatModelUiState?> = DerivedStateFlow(
        combine(
            loaded,
            catalog.flatMapLatest { it?.models ?: flowOf(emptyList()) },
            picks.choices,
            host.focus,
        ) { models, rows, choices, focus ->
            chip(ChatModelEffortInputs(models, rows, choices, focus.conversationId, supportsReasoningEffort), focus.label)
        },
        ::currentChip,
    )

    private fun currentChip(): ChatModelUiState = host.focus.value.let {
        val rows = catalog.value?.models?.value.orEmpty()
        chip(ChatModelEffortInputs(loaded.value, rows, picks.choices.value, it.conversationId, supportsReasoningEffort), it.label)
    }

    private fun chip(inputs: ChatModelEffortInputs, label: String): ChatModelUiState =
        chatModelUiState(label, chatModelOptions(inputs.models), chatModelEfforts(inputs))

    /** Starts loading [backend]'s model list (once per backend; [ChatModelCatalogLoader.reset] forgets it). */
    fun load(backend: ConversationModelBackend) {
        catalogLoader.startModelCatalogLoad(backend)
    }

    /**
     * letta-mobile-by4vm.85: binds the window's host catalog, whose rows say which reasoning efforts
     * each model takes. The catalog is otherwise read only when the picker opens, so each model-list
     * load (a connect, a host swap) re-reads it where the backend can send an effort, for as long as
     * [follow] (the binder's scope) lives. Null (no admin catalog) leaves the effort chip off.
     */
    fun bindCatalog(next: ModelCatalogRepository?, follow: CoroutineScope) {
        catalog.value = next
        catalogFollow?.cancel()
        catalogFollow = next?.let { rows ->
            follow.launch {
                loaded.collect { models -> if (models.isNotEmpty() && supportsReasoningEffort) refreshCatalogQuietly(rows) }
            }
        }
    }

    /**
     * Shows [conversation]'s model on the chip: this session's or the host's pick, else
     * [agent]'s model, else "Auto". The pick is read after the agent lookup, so a pick made while
     * the lookup was in flight is not overwritten by the agent model (letta-mobile-okvyf).
     */
    fun showModelOf(conversation: ConversationId, agent: AgentId?) {
        scope.launch {
            val agentLookup = picks[conversation.value] == null && !agent?.value.isNullOrBlank()
            val agentModel = agent?.value?.takeIf { agentLookup }?.let { id ->
                runCatching { agentByIdProvider(setOf(id)) }.getOrNull()?.get(id)?.model
            }
            showModel(conversation, agentModel)
        }
    }

    /** Shows [conversation]'s pick (this session's or the host's), else [agentModel], else "Auto". */
    fun showModel(conversation: ConversationId, agentModel: String?) {
        val label = picks.effectiveModel(conversation.value, agentModel) ?: DEFAULT_LABEL
        if (!host.isClosed) host.showModel(ConversationModelUpdate(conversation, label = label))
    }

    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) = select(handle.value, effort)

    /**
     * Applies a model override to the focused conversation, with [effort] for its reasoning effort
     * (letta-mobile-by4vm.85). A named effort shows at once and stays until the host confirms it.
     */
    fun select(model: String, effort: ReasoningEffortChoice = ReasoningEffortChoice.Unchanged) {
        if (host.isClosed) return
        val focus = host.focus.value
        val conversation = focus.conversationId ?: return
        val switch = ModelSwitch(conversation, model, effort, picks[conversation.value], focus.label)
        picks.record(conversation.value, model, effort)
        host.showModel(ConversationModelUpdate(conversation, label = model))
        scope.launch { apply(switch) }
    }

    /** Re-reads the chat's model list after the host catalog changed (letta-mobile-w4q4p.6.1). */
    suspend fun reload() {
        if (host.isClosed) return
        val backend = host.backend ?: return
        catalogLoader.startModelCatalogLoad(backend, replaceCurrent = true).await().getOrThrow()
    }

    private suspend fun apply(switch: ModelSwitch) {
        runCatching {
            // The host's record (model, effort, window) confirms the pick, as a reopen would read it.
            picks.seedFromHost(listOf(send(switch)))
            host.recordModel(switch.model)
        }.onFailure { failure -> rollBack(switch, failure) }
    }

    private suspend fun send(switch: ModelSwitch): Conversation {
        val transportModel = ModelCatalog.transportValue(loaded.value, switch.model).orEmpty()
        val backend = host.backend ?: error("This backend cannot change a conversation's model")
        return backend.switchModel(ConversationModelChange(switch.conversationId, ModelHandle(transportModel), switch.effort))
    }

    /** The host refused [switch]: the previous pick comes back (unless a newer one replaced it) and the refusal shows. */
    private fun rollBack(switch: ModelSwitch, failure: Throwable) {
        if (failure is CancellationException) throw failure
        val restored = picks.rollback(switch.conversationId.value, switch.model, switch.previousOverride)
        host.showModel(
            ConversationModelUpdate(
                conversationId = switch.conversationId,
                label = switch.previousLabel.takeIf { restored },
                error = failure.message ?: "Could not change model",
            ),
        )
    }

    companion object {
        /** The chip's label when neither a pick nor the agent names a model. */
        const val DEFAULT_LABEL = "Auto"
    }
}

/** One per-conversation switch in flight, with what to restore if the host rejects it (letta-mobile-by4vm.85). */
private data class ModelSwitch(
    val conversationId: ConversationId,
    /** The selection value picked (what the composer chip matches on). */
    val model: String,
    val effort: ReasoningEffortChoice,
    val previousOverride: String?,
    val previousLabel: String,
)

/** A catalog read that fails leaves the previous rows (or none: no effort chip); the next load tries again. */
private suspend fun refreshCatalogQuietly(catalog: ModelCatalogRepository) {
    try {
        catalog.refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Not every host answers model.list (the direct App Server session has no admin catalog).
    }
}
