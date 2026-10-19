package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_model_no_match
import com.letta.mobile.sharedui.resources.composer_model_none
import com.letta.mobile.sharedui.resources.composer_model_none_hint
import com.letta.mobile.sharedui.resources.composer_model_other_provider
import com.letta.mobile.sharedui.resources.composer_model_search
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerEmpty
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.host.PaneSize
import com.letta.mobile.ui.theme.ChatComposerDimens
import org.jetbrains.compose.resources.stringResource

/**
 * The shared, searchable, provider-grouped model picker over the owner's
 * [ChatModelUiState.options]: the shared [LettaPicker]  in a modal pane,
 * a sheet on touch and a dialog on pointer. The agent editor's Model row opens it too.
 */
@Composable
internal fun ComposerModelPickerSheet(
    model: ChatModelUiState,
    onSelect: (ChatModelOption) -> Unit,
    onDismiss: () -> Unit,
    testTag: String = ComposerTestTags.MODEL_SHEET,
) {
    var query by remember { mutableStateOf("") }
    val otherLabel = stringResource(Res.string.composer_model_other_provider)
    val sections = remember(model.options, query, otherLabel) {
        groupModelOptions(model.options, query, otherLabel).map { (provider, options) ->
            PickerSection(provider, provider, options.map { it.toPickerItem(provider) })
        }
    }
    val selected = model.currentHandle?.let { handle -> sections.flatMap { it.items }.filter { it.value.handle == handle }.map { it.key }.toSet() }
    LettaPicker(
        spec = LettaPickerSpec(
            sections = sections,
            query = query,
            selected = selected.orEmpty(),
            filterLocally = false,
            search = PickerSearch(stringResource(Res.string.composer_model_search)),
            empty = modelPickerEmpty(model, query),
        ),
        callbacks = PickerCallbacks(onQuery = { query = it }, onPick = { onSelect(it.value) }, onDismiss = onDismiss),
        pane = PickerPane(
            size = PaneSize(ChatComposerDimens.modelSheetWidth, ChatComposerDimens.modelSheetMaxHeight),
            testTag = testTag,
        ),
    )
}

/**
 * A search that matched nothing, a catalog that could not be read (its
 * reason, e.g. the on-device runtime stopped), and a catalog with no models yet read differently.
 */
@Composable
private fun modelPickerEmpty(model: ChatModelUiState, query: String): PickerEmpty {
    val unavailable = model.unavailableReason
    return when {
        query.isNotBlank() -> PickerEmpty(stringResource(Res.string.composer_model_no_match, query))
        unavailable != null -> PickerEmpty(unavailable)
        else -> PickerEmpty(stringResource(Res.string.composer_model_none), stringResource(Res.string.composer_model_none_hint))
    }
}

private fun ChatModelOption.toPickerItem(provider: String): PickerItem<ChatModelOption> = PickerItem(
    key = "$provider-$handle",
    value = this,
    title = label,
    trailing = reasoningEfforts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
)
