package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.repository.modelcontrol.ModelPickerController
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.ui.modelcontrol.ModelControlModal
import com.letta.mobile.ui.modelcontrol.ModelControlPresentation
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerContent
import com.letta.mobile.ui.modelcontrol.ModelsEditActions
import com.letta.mobile.ui.modelcontrol.ModelsEditContent
import kotlinx.coroutines.flow.StateFlow

/**
 * The composer model chip's picker on desktop (letta-mobile-w4q4p.6.1): the
 * shared [ModelPickerContent] in a centred card. With a [session] it lists the
 * host's exposed models and offers "Edit Models…"; when the host does not
 * answer the admin catalog it falls back to the chat's own model list.
 */
@Composable
internal fun DesktopModelPicker(
    session: ModelControlSession?,
    chatModels: StateFlow<List<LlmModel>>,
    selectedValue: String?,
    reloadChatModels: suspend () -> Unit,
    onSelect: (String) -> Unit,
    onEditModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(session, chatModels) {
        val fallback = ModelPickerSource.of(chatModels) { reloadChatModels() }
        val source = session?.let { ModelPickerSource.withFallback(ChatSyncedSource(it.pickerSource(), reloadChatModels), fallback) }
            ?: fallback
        ModelPickerController(scope, source)
    }
    LaunchedEffect(controller, selectedValue) { controller.setSelected(selectedValue) }
    LaunchedEffect(controller) { controller.ensureLoaded() }
    val state by controller.state.collectAsState()
    val actions = remember(controller) {
        ModelPickerActions.bind(
            controller = controller,
            onSelect = { entry ->
                // Re-picking the current model keeps its stored value (an alias stays an alias).
                if (!entry.selected) onSelect(entry.value)
                onDismiss()
            },
            onEditModels = onEditModels,
        )
    }
    ModelControlModal(ModelControlPresentation.Dialog, onDismiss = onDismiss) {
        ModelPickerContent(state = state, actions = actions, autoFocusSearch = true)
    }
}

/** "Models": show or hide each model in the picker; "Add provider…" opens Providers. */
@Composable
internal fun DesktopModelsEditor(
    session: ModelControlSession,
    onAddProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(session) { session.managementController(scope) }
    LaunchedEffect(controller) { if (controller.state.value.sections.isEmpty()) controller.refresh() }
    val state by controller.state.collectAsState()
    val actions = remember(controller) { ModelsEditActions.bind(controller, onClose = onDismiss, onAddProvider = onAddProvider) }
    ModelControlModal(ModelControlPresentation.Dialog, onDismiss = onDismiss) {
        ModelsEditContent(state = state, actions = actions, autoFocusSearch = true)
    }
}

/** A catalog load also re-reads the chat's model list, which routes the picked model. */
private class ChatSyncedSource(
    private val delegate: ModelPickerSource,
    private val reloadChatModels: suspend () -> Unit,
) : ModelPickerSource by delegate {
    override suspend fun load(force: Boolean) {
        delegate.load(force)
        runCatching { reloadChatModels() }
    }
}
