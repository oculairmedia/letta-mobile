package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.schedules.HistorySummary
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.schedules.AgendaView
import com.letta.mobile.ui.schedules.AgendaViewParams
import com.letta.mobile.ui.schedules.HistoryView
import com.letta.mobile.ui.schedules.ScheduleRailText
import com.letta.mobile.ui.schedules.TimelineView
import com.letta.mobile.ui.schedules.TimelineViewParams
import com.letta.mobile.ui.schedules.WeekView
import com.letta.mobile.ui.schedules.WeekViewParams
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate

// --- Header -------------------------------------------------------------------------------------

internal class SchedulesHeaderParams(
    val view: SchedulesView,
    val onView: (SchedulesView) -> Unit,
    val rangeLabel: String,
    /** Moves the shown week by this many weeks. */
    val onShiftWeek: (Int) -> Unit,
    /** Null hides "New schedule". */
    val onNew: (() -> Unit)?,
    val options: SchedulesPageOptions,
)

/** The title and "New schedule", then the view tabs with the week navigation beside them. */
@Composable
internal fun SchedulesHeader(params: SchedulesHeaderParams) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xl, vertical = LettaDimens.Space.md),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        if (params.options.showTitle || params.onNew != null) SchedulesTitleRow(params)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SchedulesTabs(params.view, params.onView, params.options.touch, Modifier.weight(1f))
            WeekNavigation(params)
        }
    }
}

@Composable
private fun SchedulesTitleRow(params: SchedulesHeaderParams) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (params.options.showTitle) "Schedules" else "",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        params.onNew?.let { onNew ->
            Button(onClick = onNew, modifier = Modifier.testTag(SchedulesPageTags.NEW)) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
                Spacer(Modifier.size(LettaDimens.Space.xs))
                Text("New schedule")
            }
        }
    }
}

@Composable
private fun SchedulesTabs(view: SchedulesView, onView: (SchedulesView) -> Unit, touch: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SchedulesView.entries.forEach { entry ->
            LettaChipTab(
                text = entry.label,
                active = entry == view,
                onClick = { onView(entry) },
                modifier = Modifier.testTag(SchedulesPageTags.tab(entry)).touchTarget(touch),
            )
        }
    }
}

/** Previous / range / next; kept in the layout (invisible) on views without a range so tabs never shift. */
@Composable
private fun WeekNavigation(params: SchedulesHeaderParams) {
    val shown = params.view.hasRange
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (shown) 1f else 0f)) {
        IconButton(onClick = { params.onShiftWeek(-1) }, enabled = shown, modifier = Modifier.testTag(SchedulesPageTags.PREVIOUS_WEEK)) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous week")
        }
        if (!params.options.touch) {
            Text(
                params.rangeLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(SchedulesPageTags.RANGE),
            )
        }
        IconButton(onClick = { params.onShiftWeek(1) }, enabled = shown, modifier = Modifier.testTag(SchedulesPageTags.NEXT_WEEK)) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next week")
        }
    }
}

/** The library's agents as chips, when the host has not focused one (the phone's Schedules entry). */
@Composable
internal fun SchedulesAgentChips(state: SchedulesPageState, onSelectAgent: (String) -> Unit) {
    if (!state.showsAgentChips) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = LettaDimens.Space.xl).padding(bottom = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        state.library.agents.forEach { agent ->
            val id = agent.id.value
            LettaChipTab(
                text = agent.name,
                active = id == state.library.selectedAgentId,
                onClick = { onSelectAgent(id) },
                modifier = Modifier.testTag(SchedulesPageTags.agent(id)),
            )
        }
    }
}

private fun Modifier.touchTarget(touch: Boolean): Modifier =
    if (touch) heightIn(min = LettaDimens.Control.fieldHeight) else this

// --- Body ---------------------------------------------------------------------------------------

/** The dates the views page through. */
internal class SchedulesDates(val today: LocalDate, val selected: LocalDate, val weekStart: LocalDate)

/** What the main content draws and reports. */
internal class SchedulesBody(
    val state: SchedulesPageState,
    val defs: List<ScheduleDef>,
    val view: SchedulesView,
    val clock: ScheduleClock,
    val dates: SchedulesDates,
    val selectedId: String?,
    val history: HistorySummary,
    val onRefresh: () -> Unit,
    val onSelectDate: (LocalDate) -> Unit,
    val onSelectRun: (ScheduleRun) -> Unit,
    val onSelectSchedule: (String) -> Unit,
)

/** The current view, or the loading / error / empty state when there is nothing to project. */
@Composable
internal fun SchedulesMainContent(body: SchedulesBody, modifier: Modifier) {
    val library = body.state.library
    val error = library.errorMessage
    Box(modifier) {
        when {
            body.defs.isNotEmpty() -> Box(Modifier.fillMaxSize().testTag(SchedulesPageTags.view(body.view))) { ActiveScheduleView(body) }
            error != null -> SchedulesError(error, library.isLoading, body.onRefresh)
            library.isLoading -> CenteredNote("Loading schedules…")
            else -> SchedulesEmpty(body.state)
        }
    }
}

@Composable
private fun ActiveScheduleView(body: SchedulesBody) {
    val clock = body.clock
    val dates = body.dates
    when (body.view) {
        SchedulesView.Week -> WeekView(
            WeekViewParams(
                defs = body.defs,
                weekStart = dates.weekStart,
                today = dates.today,
                now = clock.now,
                zone = clock.zone,
                selectedId = body.selectedId,
                onRunClick = body.onSelectRun,
            ),
        )
        SchedulesView.Agenda -> AgendaView(
            AgendaViewParams(
                defs = body.defs,
                selectedDate = dates.selected,
                today = dates.today,
                now = clock.now,
                zone = clock.zone,
                onSelectDate = body.onSelectDate,
                onRunClick = body.onSelectRun,
            ),
        )
        SchedulesView.Timeline -> TimelineView(
            TimelineViewParams(
                defs = body.defs,
                weekStart = dates.weekStart,
                today = dates.today,
                now = clock.now,
                zone = clock.zone,
                onLaneClick = body.onSelectSchedule,
            ),
        )
        SchedulesView.History -> HistoryView(body.history)
        SchedulesView.Rules -> SchedulesRulesView(body)
    }
}

/** Every schedule definition: its cadence, and when it fires next. A row opens its detail. */
@Composable
private fun SchedulesRulesView(body: SchedulesBody) {
    val now = body.clock.now
    val sections = remember(body.defs) { listOf(RosterSection(SchedulesPageTags.RULES_SECTION, body.defs)) }
    RosterList(
        RosterSpec(
            sections = sections,
            key = ScheduleDef::id,
            selection = RosterSelection(isSelected = { it.id == body.selectedId }, onSelect = { body.onSelectSchedule(it.id) }),
            options = RosterOptions(testTag = RosterListTag(SchedulesPageTags.RULES)),
        ),
        modifier = Modifier.fillMaxSize().padding(horizontal = LettaDimens.Space.lg),
    ) { def, row ->
        RosterListRow(
            spec = LettaListRowSpec(title = def.name, subtitle = ScheduleRailText.cadence(def), emphasized = def.active),
            row = row,
            slots = RosterRowSlots(
                end = {
                    Text(
                        if (def.active) ScheduleRailText.relativeOrDash(now, ScheduleProjection.nextRun(def, now)) else ScheduleRailText.status(false),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ),
        )
    }
}

@Composable
private fun SchedulesError(message: String, retrying: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(LettaDimens.Space.xxl).testTag(SchedulesPageTags.ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(LettaDimens.Space.sm))
        TextButton(onClick = onRetry, enabled = !retrying) { Text(if (retrying) "Retrying…" else "Retry") }
    }
}

@Composable
private fun SchedulesEmpty(state: SchedulesPageState) {
    val library = state.library
    val hint = when {
        !library.scheduleAdminAvailable && !library.cronMode -> library.emptyMessage
        state.canCreate -> "Use “New schedule” to create one."
        else -> "Schedules created elsewhere will appear here."
    }
    Column(
        Modifier.fillMaxSize().padding(LettaDimens.Space.xl).testTag(SchedulesPageTags.EMPTY),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.customColors.onSurfaceMutedColor, modifier = Modifier.size(LettaDimens.Orb.lg))
        Spacer(Modifier.height(LettaDimens.Space.md))
        Text("No schedules yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}
