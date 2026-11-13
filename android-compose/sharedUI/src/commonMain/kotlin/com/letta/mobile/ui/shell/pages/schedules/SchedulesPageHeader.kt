package com.letta.mobile.ui.shell.pages.schedules

import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaChipTabRow
import com.letta.mobile.ui.components.LettaPageHeader
import com.letta.mobile.ui.theme.LettaDimens

/** The calendar range in view: its label, paging, a way back to today, and the zone times read in. */
internal class SchedulesRange(
    val label: String,
    /** Moves the shown range by this many pages (weeks, months, agenda pages). */
    val onShift: (Int) -> Unit,
    val onToday: () -> Unit,
    val zone: String,
)

internal class SchedulesHeaderParams(
    val view: SchedulesView,
    val onView: (SchedulesView) -> Unit,
    val range: SchedulesRange,
    /** Null hides "New schedule". */
    val onNew: (() -> Unit)?,
    val options: SchedulesPageOptions,
    val refresh: SchedulesRefresh,
    /** A wide page carries the view tabs here; a phone carries them in [SchedulesViewBar]. */
    val wide: Boolean,
    val filters: SchedulesFilterParams,
)

/** Reloading the library: the header's refresh icon on pointer hosts, the body's pull on touch. */
internal class SchedulesRefresh(val refreshing: Boolean, val onRefresh: () -> Unit)

/**
 * The shared page header: the title with refresh and "New schedule" at its end; then on a wide page
 * the view tabs with the range beside them, on a phone the range alone; the agent and paused filters
 * under them; then the zone the times are in.
 */
@Composable
internal fun SchedulesHeader(params: SchedulesHeaderParams) {
    val hasRange = params.view.hasRange
    LettaPageHeader(
        title = "Schedules".takeIf { params.options.showTitle },
        primaryAction = params.onNew?.let { LettaMenuItem(label = "New schedule", onClick = it) },
        caption = "Times in ${params.range.zone}".takeIf { hasRange },
        // Touch pulls the body down instead (SchedulesMainContent's LettaRefreshable).
        refresh = { LettaRefreshAction(refreshing = params.refresh.refreshing, onRefresh = params.refresh.onRefresh) },
        controls = {
            if (params.wide) SchedulesWideControls(params) else if (hasRange) RangeNavigation(params.range, shown = true, compact = true)
            SchedulesFilters(params.filters)
        },
    )
}

/** The view tabs with the range beside them. */
@Composable
private fun SchedulesWideControls(params: SchedulesHeaderParams) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LettaChipTabRow(Modifier.weight(1f)) {
            SchedulesView.entries.forEach { entry ->
                LettaChipTab(
                    text = entry.label,
                    active = entry == params.view,
                    onClick = { params.onView(entry) },
                    modifier = Modifier.testTag(SchedulesPageTags.tab(entry)),
                )
            }
        }
        RangeNavigation(params.range, params.view.hasRange, compact = false)
    }
}

/**
 * Today / previous / range / next. On a wide page it stays in the layout (invisible) on views
 * without a range so the tabs never shift; on a phone the label takes the row's middle.
 */
@Composable
private fun RangeNavigation(range: SchedulesRange, shown: Boolean, compact: Boolean) {
    val rowModifier = if (compact) Modifier.fillMaxWidth() else Modifier
    val labelStyle = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    val onToday = if (shown) range.onToday else NO_OP
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier.alpha(if (shown) 1f else 0f),
    ) {
        LettaChipTab(
            text = "Today",
            active = false,
            onClick = onToday,
            modifier = Modifier.testTag(SchedulesPageTags.TODAY),
        )
        IconButton(onClick = { range.onShift(-1) }, enabled = shown, modifier = Modifier.testTag(SchedulesPageTags.PREVIOUS_WEEK)) {
            Icon(Lucide.ChevronLeft, contentDescription = "Previous")
        }
        Text(
            range.label,
            style = labelStyle,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = rangeLabelModifier(compact).testTag(SchedulesPageTags.RANGE),
        )
        IconButton(onClick = { range.onShift(1) }, enabled = shown, modifier = Modifier.testTag(SchedulesPageTags.NEXT_WEEK)) {
            Icon(Lucide.ChevronRight, contentDescription = "Next")
        }
    }
}

private val NO_OP: () -> Unit = {}

/** A phone's range label takes the row's middle; beside the tabs it is as wide as its text. */
private fun RowScope.rangeLabelModifier(compact: Boolean): Modifier {
    if (!compact) return Modifier
    return Modifier.weight(1f)
}

/** What the filter row shows: the library's agents, and the paused toggle when any schedule is paused. */
internal class SchedulesFilterParams(
    val state: SchedulesPageState,
    val onSelectAgent: (String) -> Unit,
    /** Null hides the toggle (nothing is paused). */
    val showPaused: Boolean?,
    val onTogglePaused: () -> Unit,
)

/** The library's agents as chips when the host has not focused one, then whether paused schedules show. */
@Composable
private fun SchedulesFilters(params: SchedulesFilterParams) {
    val state = params.state
    if (!state.showsAgentChips && params.showPaused == null) return
    LettaChipTabRow(Modifier.fillMaxWidth()) {
        SchedulesAgentChips(params)
        params.showPaused?.let { shown -> SchedulesPausedChip(shown, params.onTogglePaused) }
    }
}

@Composable
private fun SchedulesAgentChips(params: SchedulesFilterParams) {
    val library = params.state.library
    if (!params.state.showsAgentChips) return
    library.agents.forEach { agent ->
        val id = agent.id.value
        LettaChipTab(
            text = agent.name,
            active = id == library.selectedAgentId,
            onClick = { params.onSelectAgent(id) },
            modifier = Modifier.testTag(SchedulesPageTags.agent(id)),
        )
    }
}

@Composable
private fun SchedulesPausedChip(shown: Boolean, onToggle: () -> Unit) {
    val text = if (shown) "Paused: shown" else "Paused: hidden"
    LettaChipTab(text = text, active = shown, onClick = onToggle, modifier = Modifier.testTag(SchedulesPageTags.PAUSED_FILTER))
}

/** A phone's view switcher: every view as an equal, finger-sized segment along the bottom edge. */
@Composable
internal fun SchedulesViewBar(view: SchedulesView, onView: (SchedulesView) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).testTag(SchedulesPageTags.VIEW_BAR)) {
        Box(
            Modifier.fillMaxWidth().height(LettaDimens.Stroke.hairline)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xs)) {
            SchedulesView.entries.forEach { entry ->
                Box(
                    Modifier.weight(1f).heightIn(min = LettaDimens.Control.minTouchTarget).clickable { onView(entry) },
                    contentAlignment = Alignment.Center,
                ) {
                    LettaChipTab(
                        text = entry.label,
                        active = entry == view,
                        onClick = { onView(entry) },
                        modifier = Modifier.testTag(SchedulesPageTags.tab(entry)),
                    )
                }
            }
        }
    }
}
