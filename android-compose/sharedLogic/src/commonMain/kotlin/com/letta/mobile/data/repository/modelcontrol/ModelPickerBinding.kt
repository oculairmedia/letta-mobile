package com.letta.mobile.data.repository.modelcontrol

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * How a host binds the one model picker every platform opens (the composer chip, `/models`, a run
 * error's "Switch model", the agent context card's model list): where the models come from
 * ([source], with its recents), what a pick does ([onModelSelected], [onEffortSelected]: null shows
 * no effort chips), and the Models sheet behind "Edit Models…" ([editor]: null hides it). The
 * binding is also the picker's one open-state owner: [open], [editModels] and [close] drive the
 * shared ModelPickerHost.
 */
@Stable
class ModelPickerBinding(
    val source: ModelPickerSource,
    val onModelSelected: (ModelPickerEntry) -> Unit,
    val onEffortSelected: ((ModelPickerEntry, effort: String?) -> Unit)?,
    /** The Models sheet's presenter over the host's catalog. */
    val editor: ((CoroutineScope) -> ProviderManagementController)?,
    /** The Models sheet's "Add provider…": the host's Providers page; null hides it. */
    val onAddProvider: (() -> Unit)?,
    /** After the Models sheet closes: what the picker lists may have changed, so the chat re-reads its own list. */
    val onModelsEdited: suspend () -> Unit,
) {
    /** True while the picker is open. */
    var pickerOpen: Boolean by mutableStateOf(false)
        private set

    /** True while the Models sheet ("Edit Models…") is open in the picker's place. */
    var editorOpen: Boolean by mutableStateOf(false)
        private set

    fun open() {
        editorOpen = false
        pickerOpen = true
    }

    /** "Edit Models…": the picker gives way to the Models sheet. */
    fun editModels() {
        pickerOpen = false
        editorOpen = editor != null
    }

    fun close() {
        pickerOpen = false
        editorOpen = false
    }
}

/**
 * A chat's picker: the host's admin [catalog] while it answers (with "Edit Models…" over its
 * [editor]), else the [chat]'s own list ([ModelPickerSource.of]), which every load re-reads so the
 * picked model routes. [recents] (null where the host keeps none) head the list; [onAddProvider] is
 * the Models sheet's "Add provider…".
 */
fun chatModelPicker(
    catalog: ModelPickerSource?,
    editor: ((CoroutineScope) -> ProviderManagementController)?,
    chat: ModelPickerSource,
    recents: Flow<List<String>>?,
    onModelSelected: (ModelPickerEntry) -> Unit,
    onEffortSelected: (ModelPickerEntry, String?) -> Unit,
    onAddProvider: (() -> Unit)?,
): ModelPickerBinding = ModelPickerBinding(
    source = ModelPickerSource.forChat(catalog, chat, recents ?: flowOf(emptyList())),
    onModelSelected = onModelSelected,
    onEffortSelected = onEffortSelected,
    editor = editor,
    onAddProvider = onAddProvider,
    onModelsEdited = { reloadChatModelsQuietly { chat.load(ModelLoad.CACHED) } },
)
