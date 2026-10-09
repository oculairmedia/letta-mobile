package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.context.formatContextTokens
import com.letta.mobile.data.context.AgentContextCardModel
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.repository.modelcontrol.ModelPickerController
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ModelPickerGroup
import com.letta.mobile.data.repository.modelcontrol.ModelPickerState
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** Callbacks of [ModelPickerContent]; [bind] wires the controller-owned ones. */
data class ModelPickerActions(
    val onSelect: (ModelPickerEntry) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onToggleGroup: (ModelPickerGroup) -> Unit,
    val onRefresh: () -> Unit,
    /** Opens the Models sheet; null hides "Edit Models…" (a backend without exposure). */
    val onEditModels: (() -> Unit)?,
    /** Picks a model at a reasoning effort (null = provider default); null shows no effort chips. */
    val onEffortSelected: ((ModelPickerEntry, String?) -> Unit)? = null,
    val availableToolsets: List<com.letta.mobile.data.transport.appserver.AppServerToolset> = emptyList(),
    val currentToolset: String? = null,
    val onSelectToolset: ((String) -> Unit)? = null,
) {
    companion object {
        fun bind(
            controller: ModelPickerController,
            onSelect: (ModelPickerEntry) -> Unit,
            onEditModels: (() -> Unit)?,
            onEffortSelected: ((ModelPickerEntry, String?) -> Unit)? = null,
        ) = ModelPickerActions(
            onSelect = onSelect,
            onQueryChange = controller::setQuery,
            onToggleGroup = controller::toggleGroup,
            onRefresh = controller::refresh,
            onEditModels = onEditModels,
            onEffortSelected = onEffortSelected,
        )
    }
}

object ModelPickerTags {
    const val PICKER = "model_picker"
    const val SEARCH = "model_picker_search"
    const val LIST = "model_picker_list"
    const val GROUP_PREFIX = "model_picker_group_"
    const val ROW_PREFIX = "model_picker_row_"
    const val REFRESH = "model_picker_refresh"
    const val REFRESH_PROGRESS = "model_picker_refresh_progress"
    const val EDIT = "model_picker_edit"
    const val ERROR = "model_picker_error"
    const val EMPTY = "model_picker_empty"
    const val WINDOW_PREFIX = "model_picker_window_"
}

/** Tier tags sit after the name, quieter than it. */
private const val TIER_ALPHA = 0.7f

/**
 * The model picker (letta-mobile-w4q4p.6.1): "Search models", the exposed
 * models under collapsible provider headers, the current one marked, and a
 * footer with "Refresh Models" and "Edit Models…". Hosts put it in a
 * [ModelControlModal]; Android and desktop render this same content.
 */
@Composable
fun ColumnScope.ModelPickerContent(
    state: ModelPickerState,
    actions: ModelPickerActions,
    modifier: Modifier = Modifier,
    autoFocusSearch: Boolean = false,
) {
    Column(modifier = modifier.weight(1f, fill = false).testTag(ModelPickerTags.PICKER)) {
        ModelSearchField(
            query = state.query,
            onQueryChange = actions.onQueryChange,
            fieldModifier = Modifier.testTag(ModelPickerTags.SEARCH),
            autoFocus = autoFocusSearch,
        )
        val visible = state.visible
        LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().testTag(ModelPickerTags.LIST)) {
            pickerEmptyState(state, visible)
            visible.forEach { group -> pickerGroup(group, collapsed = state.isCollapsed(group), actions = actions) }
        }
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs)
                    .testTag(ModelPickerTags.ERROR),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        actions.onSelectToolset?.let { onSelect ->
            if (actions.availableToolsets.isNotEmpty()) {
                ToolsetSelectorRow(
                    toolsets = actions.availableToolsets,
                    selectedId = actions.currentToolset,
                    onSelect = onSelect,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        PickerFooter(state, actions)
    }
}

private fun LazyListScope.pickerEmptyState(state: ModelPickerState, visible: List<ModelPickerGroup>) {
    if (visible.isNotEmpty()) return
    item("empty") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.xl).testTag(ModelPickerTags.EMPTY),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            val (title, hint) = when {
                state.loading -> ModelControlStrings.LOADING_MODELS to null
                state.searching -> ModelControlStrings.noMatch(state.query.trim()) to null
                else -> ModelControlStrings.NO_MODELS to ModelControlStrings.NO_MODELS_HINT
            }
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun LazyListScope.pickerGroup(group: ModelPickerGroup, collapsed: Boolean, actions: ModelPickerActions) {
    item("group-${group.key}") { GroupHeader(group, collapsed, onToggle = { actions.onToggleGroup(group) }) }
    if (collapsed) return
    // Live catalogs can repeat a handle AND an id; the index keeps every key unique.
    itemsIndexed(group.entries, key = { index, entry -> "row-${group.key}-$index-${entry.value}" }) { _, entry ->
        PickerRow(entry, actions)
    }
}

@Composable
private fun GroupHeader(group: ModelPickerGroup, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = ModelControlStrings.groupToggle(group.title, collapsed), onClick = onToggle)
            .semantics {
                heading()
                stateDescription = if (collapsed) "Collapsed" else "Expanded"
            }
            .padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.md, top = LettaDimens.Space.md, bottom = LettaDimens.Space.xs)
            .testTag("${ModelPickerTags.GROUP_PREFIX}${group.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        ProviderCapsTitle(group.title, modifier = Modifier.weight(1f))
        DisclosureChevron(expanded = !collapsed, compact = true)
    }
}

@Composable
private fun PickerRow(entry: ModelPickerEntry, actions: ModelPickerActions) {
    val selectedColor = MaterialTheme.colorScheme.secondaryContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (entry.selected) Modifier.background(selectedColor) else Modifier)
            .selectable(selected = entry.selected, role = Role.RadioButton, onClick = { actions.onSelect(entry) })
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .testTag("${ModelPickerTags.ROW_PREFIX}${entry.value}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                entry.tier?.let { tier ->
                    Text(
                        text = ModelControlStrings.tierLabel(tier),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TIER_ALPHA),
                        maxLines = 1,
                    )
                }
            }
            if (entry.selected) {
                Icon(
                    imageVector = LettaIcons.Check,
                    contentDescription = ModelControlStrings.CURRENT_MODEL,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                )
            }
        }
        PickerRowWindow(entry)
        val onEffort = actions.onEffortSelected
        if (onEffort != null && entry.efforts.isNotEmpty()) {
            ReasoningEffortChips(
                efforts = entry.efforts,
                onSelect = { effort -> onEffort(entry, effort) },
                modifier = Modifier.padding(top = LettaDimens.Space.xs),
            )
        }
    }
}

/**
 * letta-mobile-3io8k: what the conversation already holds, in tokens, when the picker is opened from
 * the context sheet; rows then show their window and warn when it is smaller than that.
 */
val LocalModelPickerContextTokens = staticCompositionLocalOf<Int?> { null }

/** "200k context", and a warning when the conversation would not fit in it. */
@Composable
private fun PickerRowWindow(entry: ModelPickerEntry) {
    val window = entry.contextWindow ?: return
    val used = LocalModelPickerContextTokens.current
    val overflows = AgentContextCardModel.overflowsWindow(used, window)
    Text(
        text = if (overflows) ModelControlStrings.windowTooSmall(formatContextTokens(window)) else ModelControlStrings.windowLabel(formatContextTokens(window)),
        style = MaterialTheme.typography.bodySmall,
        color = if (overflows) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("${ModelPickerTags.WINDOW_PREFIX}${entry.value}"),
    )
}

@Composable
private fun PickerFooter(state: ModelPickerState, actions: ModelPickerActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = actions.onRefresh,
            enabled = !state.refreshing,
            modifier = Modifier.testTag(ModelPickerTags.REFRESH),
        ) {
            if (state.refreshing) {
                CircularProgressIndicator(
                    strokeWidth = LettaDimens.Space.hair,
                    modifier = Modifier.size(LettaDimens.Control.icon).testTag(ModelPickerTags.REFRESH_PROGRESS),
                )
            } else {
                Icon(LettaIcons.Refresh, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
            }
            Spacer(Modifier.size(LettaDimens.Space.sm))
            Text(if (state.refreshing) ModelControlStrings.REFRESHING_MODELS else ModelControlStrings.REFRESH_MODELS)
        }
        Spacer(modifier = Modifier.weight(1f))
        val onEdit = actions.onEditModels
        if (onEdit != null && state.canEditModels) {
            TextButton(onClick = onEdit, modifier = Modifier.testTag(ModelPickerTags.EDIT)) {
                Icon(LettaIcons.Settings, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
                Spacer(Modifier.size(LettaDimens.Space.sm))
                Text(ModelControlStrings.EDIT_MODELS)
            }
        }
    }
}
