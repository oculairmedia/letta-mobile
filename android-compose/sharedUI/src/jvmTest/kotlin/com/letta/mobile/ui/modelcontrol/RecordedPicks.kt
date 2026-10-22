package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import kotlinx.coroutines.flow.flowOf

/** What a test picker's picks recorded: model handles, and (handle, effort) pairs. */
internal class RecordedPicks {
    val models = mutableListOf<String>()
    val efforts = mutableListOf<Pair<String, String?>>()
}

/** A model the plain (non-catalog) picker lists, grouped under its route prefix. */
internal fun pickerModel(handle: String, label: String) =
    LlmModel(id = handle, name = label, handle = handle, displayNameOverride = label, providerType = handle.substringBefore('/'))

/** The shared picker over [models] and [recents], without a Models sheet, recording its picks into [picks]. */
internal fun testModelPicker(
    models: List<LlmModel> = emptyList(),
    picks: RecordedPicks = RecordedPicks(),
    recents: List<String> = emptyList(),
) = ModelPickerBinding(
    source = ModelPickerSource.of(flowOf(models)) {}.withRecents(flowOf(recents)),
    onModelSelected = { picks.models += it.value },
    onEffortSelected = { entry, effort -> picks.efforts += entry.value to effort },
    editor = null,
    onAddProvider = null,
    onModelsEdited = {},
)
