package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.repository.modelcontrol.ModelLoad
import com.letta.mobile.data.repository.modelcontrol.ModelPickerController
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.ui.modelcontrol.ModelControlModal
import com.letta.mobile.ui.modelcontrol.ModelControlPresentation
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerContent
import com.letta.mobile.ui.modelcontrol.ModelsEditActions
import com.letta.mobile.ui.modelcontrol.ModelsEditContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * What the desktop model picker and Models sheet run against
 * (letta-mobile-w4q4p.6.1): the host's model control when it has one, the
 * chat's own model list as the fallback, and the chat's model switch.
 */
@Immutable
internal data class DesktopModelControlHost(
    val session: ModelControlSession?,
    val chatModels: StateFlow<List<LlmModel>>,
    /** Re-reads the chat's model list, which routes the model a pick names. */
    val reloadChatModels: suspend () -> Unit,
    /** The per-conversation switch (`DesktopChatController.setConversationModel`). */
    val onModelSelected: (String) -> Unit,
    /** letta-mobile-bzvro.18: recently used models, newest first (`DesktopChatController.recentModels`). */
    val recentModels: Flow<List<String>> = flowOf(emptyList()),
) {
    /** The admin catalog while the host answers it, else the chat's list; both keep the chat's list current. */
    fun pickerSource(): ModelPickerSource {
        val fallback = ModelPickerSource.of(chatModels) { reloadChatModels() }
        val catalog = session?.pickerSource()
            ?: return fallback.withRecents(recentModels)
        return ModelPickerSource.withFallback(ChatSyncedSource(catalog, reloadChatModels), fallback).withRecents(recentModels)
    }
}

/** Which of the two model surfaces is open; the app's overlay stack owns the flags. */
internal interface DesktopModelSurfaces {
    var modelPicker: Boolean
    var modelsEditor: Boolean
}

/**
 * The composer chip's picker and the "Models" sheet behind its "Edit Models…",
 * both the shared sharedUI content in a centred card. [onOpenProviders] is the
 * sheet's "Add provider…".
 */
@Composable
internal fun DesktopModelControlOverlays(
    surfaces: DesktopModelSurfaces,
    host: DesktopModelControlHost,
    selectedValue: String?,
    onOpenProviders: () -> Unit,
) {
    if (surfaces.modelPicker) {
        DesktopModelPicker(
            host = host,
            selectedValue = selectedValue,
            onEditModels = {
                surfaces.modelPicker = false
                surfaces.modelsEditor = true
            },
            onDismiss = { surfaces.modelPicker = false },
        )
    }
    val session = host.session
    if (surfaces.modelsEditor && session != null) {
        val scope = rememberCoroutineScope()
        DesktopModelsEditor(
            session = session,
            onAddProvider = {
                surfaces.modelsEditor = false
                onOpenProviders()
            },
            onDismiss = {
                surfaces.modelsEditor = false
                // What the picker shows may have changed; the chat routes picks through its own list.
                scope.launch { reloadQuietly(host.reloadChatModels) }
            },
        )
    }
}

@Composable
private fun DesktopModelPicker(
    host: DesktopModelControlHost,
    selectedValue: String?,
    onEditModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(host) { ModelPickerController(scope, host.pickerSource()) }
    LaunchedEffect(controller, selectedValue) { controller.setSelected(selectedValue) }
    LaunchedEffect(controller) { controller.ensureLoaded() }
    val state by controller.state.collectAsState()
    val actions = remember(controller) {
        ModelPickerActions.bind(
            controller = controller,
            onSelect = { entry ->
                // Re-picking the current model keeps its stored value (an alias stays an alias).
                if (!entry.selected) host.onModelSelected(entry.value)
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
private fun DesktopModelsEditor(
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
    override suspend fun load(mode: ModelLoad) {
        delegate.load(mode)
        reloadQuietly(reloadChatModels)
    }
}

/** The chat's list is a mirror; failing to re-read it must not fail the picker. */
private suspend fun reloadQuietly(reload: suspend () -> Unit) {
    try {
        reload()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The chat keeps its previous list; the next picker load tries again.
    }
}
