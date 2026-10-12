package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.context.formatContextTokens
import com.letta.mobile.ui.context.AgentContextStrings
import com.letta.mobile.data.context.AgentContextCardModel
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.repository.modelcontrol.ModelPickerController
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ModelPickerGroup
import com.letta.mobile.data.repository.modelcontrol.ModelPickerState
import com.letta.mobile.ui.components.picker.LettaPickerContent
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerEmpty
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerLevel
import com.letta.mobile.ui.components.picker.PickerLoad
import com.letta.mobile.ui.components.picker.PickerRowState
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.components.picker.PickerSlots
import com.letta.mobile.ui.components.picker.PickerTags
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.providers.icons.ProviderIcons
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
    /**
     * letta-mobile-pt1ze.5: set when the picker is the second level of a pane (the agent settings
     * sheet); it then heads the list with a back arrow, and Escape goes back.
     */
    val onBack: (() -> Unit)? = null,
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
    const val GROUP_LOGO_PREFIX = "model_picker_group_logo_"
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

/** The picker's former tags, kept on the shared [LettaPickerContent] it now composes. */
private val ModelPickerPickerTags = PickerTags(
    root = ModelPickerTags.PICKER,
    search = ModelPickerTags.SEARCH,
    list = ModelPickerTags.LIST,
    sectionPrefix = ModelPickerTags.GROUP_PREFIX,
    rowPrefix = ModelPickerTags.ROW_PREFIX,
    empty = ModelPickerTags.EMPTY,
    error = ModelPickerTags.ERROR,
)

/**
 * The model picker (letta-mobile-w4q4p.6.1): "Search models", the exposed
 * models under collapsible provider headers, the current one marked, and a
 * footer with "Refresh Models" and "Edit Models…". It composes the shared
 * [LettaPickerContent] (letta-mobile-ckmub.3.6): the search stays pinned and the list owns the
 * only scroll. Hosts put it in a [com.letta.mobile.ui.host.LettaPane] (LettaPaneKind.Modal), or
 * show it as the second level of a pane they own (the agent settings sheet, [ModelPickerActions.onBack]).
 */
@Composable
fun ColumnScope.ModelPickerContent(
    state: ModelPickerState,
    actions: ModelPickerActions,
    modifier: Modifier = Modifier,
    autoFocusSearch: Boolean = false,
) {
    val level = actions.onBack?.let { PickerLevel(ModelControlStrings.MODEL, onBack = it) }
    val groups = state.visible
    val spec = LettaPickerSpec(
        sections = groups.map { group -> group.toSection(state.isCollapsed(group)) },
        query = state.query,
        load = state.error?.let(PickerLoad::Failed) ?: PickerLoad.Ready,
        filterLocally = false,
        search = PickerSearch(ModelControlStrings.SEARCH_MODELS, autoFocus = autoFocusSearch),
        empty = emptyState(state),
        level = level,
        tags = ModelPickerPickerTags,
    )
    val callbacks = PickerCallbacks<ModelPickerEntry>(
        onQuery = actions.onQueryChange,
        onPick = { actions.onSelect(it.value) },
        onToggleSection = { section -> groups.firstOrNull { it.key == section.key }?.let(actions.onToggleGroup) },
    )
    val slots = PickerSlots<ModelPickerEntry>(
        row = { item, row -> PickerRow(item.value, row, actions) },
        footer = { PickerFooterBar(state, actions) },
        sectionLeading = { section -> GroupLogo(section) },
    )
    LettaPickerContent(spec, callbacks, modifier, slots)
}

private fun ModelPickerGroup.toSection(collapsed: Boolean): PickerSection<ModelPickerEntry> = PickerSection(
    key = key,
    title = title,
    // Live catalogs can repeat a handle AND an id; the index keeps every key unique.
    items = entries.mapIndexed { index, entry -> PickerItem("$key-$index-${entry.value}", entry, entry.displayName) },
    collapsed = collapsed,
)

/** The provider's logo before its group title; nothing for "Recent" or a provider without one. */
@Composable
private fun GroupLogo(section: PickerSection<ModelPickerEntry>) {
    val logo = ProviderIcons.brandOfAny(listOf(section.key, section.title))?.logo ?: return
    Icon(
        logo,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(LettaDimens.Control.iconSm).testTag("${ModelPickerTags.GROUP_LOGO_PREFIX}${section.key}"),
    )
}

private fun emptyState(state: ModelPickerState): PickerEmpty = when {
    state.loading -> PickerEmpty(ModelControlStrings.LOADING_MODELS)
    state.searching -> PickerEmpty(ModelControlStrings.noMatch(state.query.trim()))
    else -> PickerEmpty(ModelControlStrings.NO_MODELS, ModelControlStrings.NO_MODELS_HINT)
}

@Composable
private fun PickerFooterBar(state: ModelPickerState, actions: ModelPickerActions) {
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

@Composable
private fun PickerRow(entry: ModelPickerEntry, row: PickerRowState, actions: ModelPickerActions) {
    val background = when {
        entry.selected -> MaterialTheme.colorScheme.secondaryContainer
        row.highlighted -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .selectable(selected = entry.selected, role = Role.RadioButton, onClick = row.onClick)
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
                PickerRowWindow(entry)
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
        PickerRowOverflow(entry)
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

/** The window beside the name ("200k"), in the error colour when the conversation would not fit. */
@Composable
private fun PickerRowWindow(entry: ModelPickerEntry) {
    val window = entry.contextWindow ?: return
    Text(
        text = formatContextTokens(window),
        style = MaterialTheme.typography.bodySmall,
        color = if (entry.overflows()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.testTag("${ModelPickerTags.WINDOW_PREFIX}${entry.value}"),
    )
}

/** Under the row only when the conversation would not fit in the model's window. */
@Composable
private fun PickerRowOverflow(entry: ModelPickerEntry) {
    val window = entry.contextWindow ?: return
    if (!entry.overflows()) return
    Text(
        text = AgentContextStrings.windowLine(window, overflows = true),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ModelPickerEntry.overflows(): Boolean =
    AgentContextCardModel.overflowsWindow(LocalModelPickerContextTokens.current, contextWindow)

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
