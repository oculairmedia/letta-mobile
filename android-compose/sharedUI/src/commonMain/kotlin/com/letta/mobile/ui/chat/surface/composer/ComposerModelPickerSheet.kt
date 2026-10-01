package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_model_no_match
import com.letta.mobile.sharedui.resources.composer_model_other_provider
import com.letta.mobile.sharedui.resources.composer_model_search
import com.letta.mobile.sharedui.resources.composer_model_selected
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * The shared, searchable, provider-grouped model picker. Lifted from desktop's
 * DesktopModelPickerSheet: its Jewel search field is a Material 3 field here, and it lists the
 * owner's [ChatModelUiState.options] rather than the raw model catalog.
 */
@Composable
internal fun ComposerModelPickerSheet(
    model: ChatModelUiState,
    onSelect: (ChatModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val otherLabel = stringResource(Res.string.composer_model_other_provider)
    val groups = remember(model.options, query, otherLabel) { groupModelOptions(model.options, query, otherLabel) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(max = ChatComposerDimens.modelSheetWidth)
                .fillMaxWidth()
                .heightIn(max = ChatComposerDimens.modelSheetMaxHeight)
                .testTag(ComposerTestTags.MODEL_SHEET),
            shape = RoundedCornerShape(LettaDimens.Radius.lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = LettaDimens.Space.sm,
        ) {
            Column {
                ModelSearchField(query = query, onQueryChange = { query = it })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModelList(groups = groups, query = query, currentHandle = model.currentHandle, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit) {
    // The sheet opens to search, so the field takes focus at once.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.composer_model_search)) },
        leadingIcon = {
            Icon(
                imageVector = LettaIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(LettaDimens.Space.md)
            .focusRequester(focusRequester),
    )
}

@Composable
private fun ModelList(
    groups: List<Pair<String, List<ChatModelOption>>>,
    query: String,
    currentHandle: String?,
    onSelect: (ChatModelOption) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = ChatComposerDimens.modelListMaxHeight)) {
        if (groups.isEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.composer_model_no_match, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(LettaDimens.Space.xl),
                )
            }
        }
        groups.forEach { (provider, options) ->
            item(key = "h-$provider") { ComposerPopoverHeader(provider) }
            items(options, key = { "$provider-${it.handle}" }) { option ->
                ModelRow(option = option, selected = option.handle == currentHandle, onClick = { onSelect(option) })
            }
        }
    }
}

@Composable
private fun ModelRow(option: ChatModelOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (option.reasoningEfforts.isNotEmpty()) {
            Text(
                text = option.reasoningEfforts.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = LettaIcons.Check,
                contentDescription = stringResource(Res.string.composer_model_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
    }
}
