package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ModelLoad
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import kotlinx.coroutines.flow.Flow
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
) = recordingPicker(ModelPickerSource.of(flowOf(models)) {}.withRecents(flowOf(recents)), picks)

/** The shared picker over a catalog whose [models] advertise reasoning efforts, recording its picks into [picks]. */
internal fun testCatalogPicker(models: List<CatalogModel>, picks: RecordedPicks) = recordingPicker(
    object : ModelPickerSource {
        override val providers: Flow<List<ConnectableProvider>> = flowOf(emptyList())
        override val models: Flow<List<CatalogModel>> = flowOf(models)
        override val canEditModels: Flow<Boolean> = flowOf(false)

        override suspend fun load(mode: ModelLoad) = Unit
    },
    picks,
)

private fun recordingPicker(source: ModelPickerSource, picks: RecordedPicks) = ModelPickerBinding(
    source = source,
    onModelSelected = { picks.models += it.value },
    onEffortSelected = { entry, effort -> picks.efforts += entry.value to effort },
    editor = null,
    onAddProvider = null,
    onModelsEdited = {},
)
