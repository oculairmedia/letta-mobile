package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.repository.modelcontrol.chatModelPicker
import kotlinx.coroutines.flow.Flow

/**
 * The picker of a chat whose models are the shared [ConversationModels] (desktop and the web): the
 * host's admin catalog ([session]) while it answers, else the chat's own list, with [recents] first.
 * A pick switches the open conversation's model (an alias stays an alias), at an effort when the row's options
 * name one (none: the provider default).
 */
fun conversationModelPicker(
    session: ModelControlSession?,
    models: ConversationModels,
    recents: Flow<List<String>>?,
    onAddProvider: () -> Unit,
): ModelPickerBinding = chatModelPicker(
    catalog = session?.pickerSource(),
    editor = session?.let { it::managementController },
    chat = ModelPickerSource.of(models.available) { models.reload() },
    recents = recents,
    onModelSelected = { entry -> models.select(entry.value) },
    onEffortSelected = { entry, effort ->
        models.select(entry.value, effort?.let(ReasoningEffortChoice::Named) ?: ReasoningEffortChoice.ProviderDefault)
    },
    onAddProvider = onAddProvider,
)
