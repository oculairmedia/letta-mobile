package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.reasoningEffortsOf
import kotlinx.collections.immutable.toImmutableList

/*
 * letta-mobile-ckmub.17.2: the composer model chip's pure mapping (moved from desktop's session
 * mapping), so every host that binds [ConversationModels] draws the same chip.
 */

/** Model picker options: display label to route-stable selection token. */
fun chatModelOptions(availableModels: List<LlmModel>): List<Pair<String, String>> =
    availableModels.map { model ->
        val label = model.displayNameOverride?.takeIf { it.isNotBlank() } ?: model.name
        label to ModelCatalog.selectionValue(availableModels, model)
    }

/**
 * letta-mobile-by4vm.85: the reasoning efforts the composer offers. [byValue] holds each option's
 * efforts by selection value (empty where the backend cannot send an effort, so no effort chip
 * shows); [current] is the selected conversation's effort, this session's pick else the host's.
 */
data class ChatModelEfforts(
    val byValue: Map<String, List<String>> = emptyMap(),
    val current: String? = null,
)

/** Everything [chatModelEfforts] reads, at one instant. */
data class ChatModelEffortInputs(
    val models: List<LlmModel>,
    /** The host catalog's rows (`model.list`), which advertise each model's efforts. */
    val catalog: List<CatalogModel>,
    val choices: ConversationModelChoices,
    val selectedConversation: ConversationId?,
    /** False where the backend cannot send an effort with a switch: no option offers one then. */
    val supported: Boolean,
)

/** letta-mobile-by4vm.85: each option's efforts (by selection value) and the conversation's effort. */
fun chatModelEfforts(inputs: ChatModelEffortInputs): ChatModelEfforts {
    if (!inputs.supported) return ChatModelEfforts()
    val byValue = inputs.models.mapNotNull { model ->
        reasoningEffortsOf(inputs.catalog, model).takeIf { it.isNotEmpty() }
            ?.let { ModelCatalog.selectionValue(inputs.models, model) to it }
    }.toMap()
    return ChatModelEfforts(byValue = byValue, current = inputs.selectedConversation?.let { inputs.choices.efforts[it.value] })
}

/**
 * The composer model chip. The chip's label is either a display label or (after a switch) the
 * selection value, so the current option is matched on either.
 */
fun chatModelUiState(
    label: String,
    modelOptions: List<Pair<String, String>>,
    efforts: ChatModelEfforts = ChatModelEfforts(),
): ChatModelUiState {
    val options = modelOptions.map { (optionLabel, value) ->
        ChatModelOption(handle = value, label = optionLabel, reasoningEfforts = efforts.byValue[value].orEmpty().toImmutableList())
    }
    val current = options.firstOrNull { it.handle == label || it.label == label }
    return ChatModelUiState(
        currentHandle = current?.handle,
        currentLabel = current?.label ?: label,
        currentEffort = efforts.current,
        options = options.toImmutableList(),
    )
}
