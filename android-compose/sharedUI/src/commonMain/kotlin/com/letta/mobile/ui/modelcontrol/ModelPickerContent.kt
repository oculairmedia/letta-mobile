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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.components.isPointerHost
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
import com.letta.mobile.ui.theme.LettaChip
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSpinner

/** Callbacks of [ModelPickerContent]; [bind] wires the controller-owned ones. */
data class ModelPickerActions(
    val onSelect: (ModelPickerEntry) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onToggleGroup: (ModelPickerGroup) -> Unit,
    val onRefresh: () -> Unit,
    /** Opens the Models sheet; null hides "Edit Models…" (a backend without exposure). */
    val onEditModels: (() -> Unit)?,
    /** Picks a model at a reasoning effort (null = provider default); null offers no effort options. */
    val onEffortSelected: ((ModelPickerEntry, String?) -> Unit)? = null,
    /** The effort last picked for a model in this session, shown in force before its catalog default. */
    val rememberedEffort: (ModelPickerEntry) -> String? = { null },
    val availableToolsets: List<com.letta.mobile.data.transport.appserver.AppServerToolset> = emptyList(),
    val currentToolset: String? = null,
    val onSelectToolset: ((String) -> Unit)? = null,
    /**
     * Set when the picker is the second level of a pane (the agent settings
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
            rememberedEffort: (ModelPickerEntry) -> String? = { null },
        ) = ModelPickerActions(
            onSelect = onSelect,
            onQueryChange = controller::setQuery,
            onToggleGroup = controller::toggleGroup,
            onRefresh = controller::refresh,
            onEditModels = onEditModels,
            onEffortSelected = onEffortSelected,
            rememberedEffort = rememberedEffort,
        )
    }
}

object ModelPickerTags {
    const val PICKER = "model_picker"

    /** The shared picker's pane ([ModelPickerHost]). */
    const val PANE = "model_picker_pane"
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
 * The model picker: "Search models", the exposed
 * models under collapsible provider headers, the current one marked, and a
 * footer with "Refresh Models" and "Edit Models…". It composes the shared
 * [LettaPickerContent]: the search stays pinned and the list owns the
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
    // The host may hand over new actions on every composition; the open options outlive them.
    val latest by rememberUpdatedState(actions)
    val allOptions = remember { EffortOptionsState { entry, effort -> latest.onEffortSelected?.invoke(entry, effort) } }
    val options = allOptions.takeIf { actions.onEffortSelected != null }
    val touch = !isPointerHost()
    val callbacks = PickerCallbacks<ModelPickerEntry>(
        onQuery = actions.onQueryChange,
        onPick = { actions.onSelect(it.value) },
        onToggleSection = { section -> groups.firstOrNull { it.key == section.key }?.let(actions.onToggleGroup) },
        onKey = options?.let { state -> { event, item -> state.onKey(event, item?.let { effortTarget(it, actions) }) } },
    )
    val slots = PickerSlots<ModelPickerEntry>(
        row = { item, row -> PickerRow(PickerRowModel(item, row, effortTarget(item, actions), options, touch)) },
        footer = { PickerFooterBar(state, actions) },
        sectionLeading = { section -> GroupLogo(section) },
    )
    LettaPickerContent(spec, callbacks, modifier, slots)
    options?.let { state ->
        CloseWhenUnhovered(state)
        val open = state.target
        if (touch && open != null) EffortOptionsSheet(state, open)
    }
}

/** [item]'s effort options, when its model offers any and the host can pick an effort. */
private fun effortTarget(item: PickerItem<ModelPickerEntry>, actions: ModelPickerActions): EffortTarget? {
    actions.onEffortSelected ?: return null
    val entry = item.value
    val menu = entry.effortMenu(actions.rememberedEffort(entry))
    return if (menu.isEmpty) null else EffortTarget(item.key, entry, menu)
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

/** One row as drawn: the picker's row state, its effort options (null: none) and whether the host is touch. */
private class PickerRowModel(
    val item: PickerItem<ModelPickerEntry>,
    val row: PickerRowState,
    val effort: EffortTarget?,
    val options: EffortOptionsState?,
    val touch: Boolean,
) {
    val entry: ModelPickerEntry get() = item.value
    val optionsOpen: Boolean get() = effort != null && options?.target?.key == effort.key

    /** Touch: what opens the options sheet (the pill and a long-press); null without options or on a pointer. */
    val openSheet: (() -> Unit)?
        get() = if (touch && effort != null && options != null) ({ options.open(effort) }) else null
}

/**
 * One line: the name, then quietly its effort and context window, and a check on the current model.
 * A pointer hover opens the effort flyout beside it; on touch a trailing effort pill or a
 * long-press opens the same options in a sheet.
 */
@Composable
private fun PickerRow(model: PickerRowModel) {
    val entry = model.entry
    val hover = remember { MutableInteractionSource() }
    FollowRowHover(model, hover)
    val openSheet = model.openSheet
    val background = when {
        entry.selected -> MaterialTheme.colorScheme.secondaryContainer
        model.row.highlighted || model.optionsOpen -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .hoverable(hover)
            .combinedClickable(role = Role.RadioButton, onClick = model.row.onClick, onLongClick = openSheet)
            .semantics { selected = entry.selected }
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
                // The pill names the effort on touch; elsewhere it trails the name.
                PickerRowDetails(entry, effort = if (openSheet != null) null else model.effort?.menu?.current ?: entry.tier?.effort)
            }
            openSheet?.let { EffortPill(entry, model.effort?.menu?.current, it) }
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
        PickerRowFlyout(model)
    }
}

/** Pointer: reports the row's hover to its options, which open on it. */
@Composable
private fun FollowRowHover(model: PickerRowModel, hover: MutableInteractionSource) {
    val effort = model.effort ?: return
    val options = model.options ?: return
    if (model.touch) return
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered, effort) { options.rowHover(effort, hovered) }
}

/** Pointer: the open options' flyout, anchored to this row. */
@Composable
private fun PickerRowFlyout(model: PickerRowModel) {
    val effort = model.effort ?: return
    val options = model.options ?: return
    if (!model.touch && model.optionsOpen) EffortFlyout(options, effort)
}

/** After the name, quieter than it: the effort in force ("Med") and the context window, dot-separated. */
@Composable
private fun PickerRowDetails(entry: ModelPickerEntry, effort: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TIER_ALPHA)
    effort?.let { Text(effortLabel(it), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1) }
    if (effort != null && entry.contextWindow != null) {
        Text(DETAIL_SEPARATOR, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
    }
    PickerRowWindow(entry)
}

private const val DETAIL_SEPARATOR = "·"

/** Touch: the effort in force with a chevron; a tap opens the model's options sheet. */
@Composable
private fun EffortPill(entry: ModelPickerEntry, effort: String?, onClick: () -> Unit) {
    LettaChip(
        text = effortLabel(effort),
        onClick = onClick,
        trailingIcon = LettaIcons.ChevronRight,
        modifier = Modifier
            .semantics { contentDescription = ModelControlStrings.effortOptionsOf(entry.displayName) }
            .testTag("${ModelEffortTags.PILL_PREFIX}${entry.value}"),
    )
}

/**
 * What the conversation already holds, in tokens, when the picker is opened from
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
                LettaSpinner(Modifier.testTag(ModelPickerTags.REFRESH_PROGRESS))
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
