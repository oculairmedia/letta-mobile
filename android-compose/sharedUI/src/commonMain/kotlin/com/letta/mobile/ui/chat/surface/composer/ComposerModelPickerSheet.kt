package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_model_no_match
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
 * [ChatModelUiState.options]: the shared [LettaPicker] (letta-mobile-ckmub.3.6) in a modal pane,
 * a sheet on touch and a dialog on pointer.
 */
@Composable
internal fun ComposerModelPickerSheet(
    model: ChatModelUiState,
    onSelect: (ChatModelOption) -> Unit,
    onDismiss: () -> Unit,
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
            empty = PickerEmpty(stringResource(Res.string.composer_model_no_match, query)),
        ),
        callbacks = PickerCallbacks(onQuery = { query = it }, onPick = { onSelect(it.value) }, onDismiss = onDismiss),
        pane = PickerPane(
            size = PaneSize(ChatComposerDimens.modelSheetWidth, ChatComposerDimens.modelSheetMaxHeight),
            testTag = ComposerTestTags.MODEL_SHEET,
        ),
    )
}

private fun ChatModelOption.toPickerItem(provider: String): PickerItem<ChatModelOption> = PickerItem(
    key = "$provider-$handle",
    value = this,
    title = label,
    trailing = reasoningEfforts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
)
