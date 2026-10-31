package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
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
import com.composables.icons.lucide.Plus
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

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
)

/** Reloading the library: the header's refresh icon on pointer hosts, the body's pull on touch. */
internal class SchedulesRefresh(val refreshing: Boolean, val onRefresh: () -> Unit)

/**
 * The title and "New schedule"; then on a wide page the view tabs with the range beside them, on a
 * phone the range alone; then the zone the times are in.
 */
@Composable
internal fun SchedulesHeader(params: SchedulesHeaderParams) {
    val inset = if (params.wide) LettaDimens.Space.xl else LettaDimens.Space.lg
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        SchedulesTitleRow(params)
        if (params.wide) SchedulesWideRangeRow(params) else SchedulesPhoneRangeRow(params)
        SchedulesZoneLine(params)
    }
}

/** The view tabs with the range beside them, and the refresh icon pointer hosts reload with. */
@Composable
private fun SchedulesWideRangeRow(params: SchedulesHeaderParams) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SchedulesTabs(params.view, params.onView, Modifier.weight(1f))
        RangeNavigation(params.range, params.view.hasRange, compact = false)
        // Touch pulls the body down instead (SchedulesMainContent's LettaRefreshable).
        LettaRefreshAction(refreshing = params.refresh.refreshing, onRefresh = params.refresh.onRefresh)
    }
}

/** A phone's range alone (its tabs are in [SchedulesViewBar]), on views that have one. */
@Composable
private fun SchedulesPhoneRangeRow(params: SchedulesHeaderParams) {
    if (!params.view.hasRange) return
    RangeNavigation(params.range, shown = true, compact = true)
}

/** The zone the times are read in, on views that show times. */
@Composable
private fun SchedulesZoneLine(params: SchedulesHeaderParams) {
    if (!params.view.hasRange) return
    Text(
        "Times in ${params.range.zone}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.customColors.onSurfaceMutedColor,
        modifier = Modifier.testTag(SchedulesPageTags.ZONE),
    )
}

@Composable
private fun SchedulesTitleRow(params: SchedulesHeaderParams) {
    val showTitle = params.options.showTitle
    if (!showTitle && params.onNew == null) return
    val title = if (showTitle) "Schedules" else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        params.onNew?.let { onNew ->
            Button(onClick = onNew, modifier = Modifier.testTag(SchedulesPageTags.NEW)) {
                Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
                Spacer(Modifier.size(LettaDimens.Space.xs))
                Text("New schedule")
            }
        }
    }
}

@Composable
private fun SchedulesTabs(view: SchedulesView, onView: (SchedulesView) -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SchedulesView.entries.forEach { entry ->
            LettaChipTab(
                text = entry.label,
                active = entry == view,
                onClick = { onView(entry) },
                modifier = Modifier.testTag(SchedulesPageTags.tab(entry)),
            )
        }
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
internal fun SchedulesFilters(params: SchedulesFilterParams) {
    val state = params.state
    if (!state.showsAgentChips && params.showPaused == null) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = LettaDimens.Space.lg).padding(bottom = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
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
