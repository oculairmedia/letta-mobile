package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.repository.modelcontrol.ConversationEffortGateway
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ConversationModelSelections
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.desktop.buildModelOptions
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatModelSource
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.DerivedStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What [DesktopConversationModels] reads and writes on the controller that owns it. */
internal interface DesktopModelHost {
    val gateway: DesktopChatGateway?
    val isClosed: Boolean
    val surface: StateFlow<DesktopChatSurfaceState>

    /** Where a confirmed switch is remembered among the recently used models. */
    val management: DesktopConversationManagement

    fun updateSurface(transform: (DesktopChatSurfaceState) -> DesktopChatSurfaceState)
}

/**
 * letta-mobile-by4vm.98.1: the chat's models in one place: the gateway's model list, each
 * conversation's pick and effort, the per-conversation switch, and the composer chip
 * ([ChatModelSource]) with the reasoning efforts the host catalog advertises.
 */
class DesktopConversationModels internal constructor(
    private val scope: CoroutineScope,
    private val host: DesktopModelHost,
    agentByIdProvider: suspend (agentIds: Set<String>) -> Map<String, Agent>,
) : ChatModelSource {
    private val loaded = MutableStateFlow<List<LlmModel>>(emptyList())
    private val catalog = MutableStateFlow<ModelCatalogRepository?>(null)
    private var catalogFollow: Job? = null

    // Per-conversation model overrides: the host's (seeded from each conversation list, dxz5s) and
    // this session's picks. The effective composer model otherwise comes from the conversation's agent.
    internal val picks = ConversationModelSelections()

    internal val catalogLoader = DesktopChatModelCatalogHelper(
        scope = scope,
        agentByIdProvider = agentByIdProvider,
        onModelsLoaded = { if (!host.isClosed) loaded.value = it },
        getSelectedConversationAgentId = { host.surface.value.selectedConversation?.agentId },
    )

    /** The active gateway's models. */
    val available: StateFlow<List<LlmModel>> = loaded.asStateFlow()

    /** Each conversation's own model and window; the context chip and card size their window from it. */
    val selections: StateFlow<ConversationModelChoices> get() = picks.choices

    /**
     * letta-mobile-by4vm.85: true when the active gateway can send a reasoning effort with a
     * switch; the composer shows its effort chip only then.
     */
    val supportsReasoningEffort: Boolean
        get() = host.gateway is ConversationEffortGateway

    @OptIn(ExperimentalCoroutinesApi::class)
    override val model: StateFlow<ChatModelUiState?> = DerivedStateFlow(
        combine(
            loaded,
            catalog.flatMapLatest { it?.models ?: flowOf(emptyList()) },
            picks.choices,
            host.surface.map { it.selectedConversationId to it.composerModelLabel }.distinctUntilChanged(),
        ) { models, rows, choices, (selectedId, label) ->
            chip(DesktopModelEffortInputs(models, rows, choices, selectedId, supportsReasoningEffort), label)
        },
        ::currentChip,
    )

    private fun currentChip(): ChatModelUiState = host.surface.value.let {
        val rows = catalog.value?.models?.value.orEmpty()
        chip(DesktopModelEffortInputs(loaded.value, rows, picks.choices.value, it.selectedConversationId, supportsReasoningEffort), it.composerModelLabel)
    }

    private fun chip(inputs: DesktopModelEffortInputs, label: String): ChatModelUiState =
        desktopChatModelUiState(label, buildModelOptions(inputs.models), desktopModelEfforts(inputs))

    /**
     * letta-mobile-by4vm.85: binds the window's host catalog, whose rows say which reasoning efforts
     * each model takes. The catalog is otherwise read only when the picker opens, so each model-list
     * load (a connect, a host swap) re-reads it where the gateway can send an effort, for as long as
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

    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) = select(handle.value, effort)

    /**
     * Applies a model override to the selected conversation, with [effort] for its reasoning effort
     * (letta-mobile-by4vm.85). A named effort shows at once and stays until the host confirms it.
     */
    fun select(model: String, effort: ReasoningEffortChoice = ReasoningEffortChoice.Unchanged) {
        if (host.isClosed) return
        val surface = host.surface.value
        val conversationId = surface.selectedConversationId ?: return
        val switch = DesktopModelSwitch(ConversationId(conversationId), model, effort, picks[conversationId], surface.composerModelLabel)
        picks.record(conversationId, model, effort)
        host.updateSurface { it.copy(composerModelLabel = model) }
        scope.launch { apply(switch) }
    }

    /** Re-reads the chat's model list after the host catalog changed (letta-mobile-w4q4p.6.1). */
    suspend fun reload() {
        if (host.isClosed) return
        val extras = host.gateway as? ChatGatewayExtras ?: return
        catalogLoader.startModelCatalogLoad(extras, replaceCurrent = true).await().getOrThrow()
    }

    private suspend fun apply(switch: DesktopModelSwitch) {
        val conversationId = switch.conversationId.value
        try {
            val transportModel = ModelCatalog.transportValue(loaded.value, switch.model).orEmpty()
            val gw = host.gateway ?: error("This backend cannot change a conversation's model")
            val recorded = gw.sendConversationModel(switch.conversationId, ModelHandle(transportModel), switch.effort)
            // The host's record (model, effort, window) confirms the pick, as a reopen would read it.
            picks.seedFromHost(listOf(recorded))
            host.management.recordModel(switch.model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val restored = picks.rollback(conversationId, switch.model, switch.previousOverride)
            host.updateSurface { surface ->
                val label = if (restored && surface.selectedConversationId == conversationId) switch.previousLabel else surface.composerModelLabel
                surface.copy(composerModelLabel = label, errorMessage = t.message ?: "Could not change model")
            }
        }
    }
}

/**
 * letta-mobile-by4vm.85: one per-conversation switch in flight, with what to restore if the host
 * rejects it.
 */
private data class DesktopModelSwitch(
    val conversationId: ConversationId,
    /** The selection value picked (what the composer chip matches on). */
    val model: String,
    val effort: ReasoningEffortChoice,
    val previousOverride: String?,
    val previousLabel: String,
)

/**
 * Sends a switch: `model.update` with the effort where the gateway carries one
 * ([ConversationEffortGateway]), else the model-only [ChatGatewayExtras.setConversationModel]. A
 * gateway without the effort refuses an effort pick rather than drop it silently.
 */
private suspend fun DesktopChatGateway.sendConversationModel(
    conversation: ConversationId,
    model: ModelHandle,
    effort: ReasoningEffortChoice,
): Conversation {
    if (this is ConversationEffortGateway) {
        return updateConversationModel(ConversationModelChange(conversation, model, effort))
    }
    val extras = this as? ChatGatewayExtras ?: error("This backend cannot change a conversation's model")
    check(effort == ReasoningEffortChoice.Unchanged) { "This backend cannot change a conversation's reasoning effort" }
    return extras.setConversationModel(conversation.value, model.value)
}

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
