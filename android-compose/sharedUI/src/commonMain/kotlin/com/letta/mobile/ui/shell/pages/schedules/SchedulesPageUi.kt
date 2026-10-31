package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CalendarDay
import com.letta.mobile.data.schedules.CalendarLabels
import com.letta.mobile.data.schedules.CalendarQuery
import com.letta.mobile.data.schedules.CalendarRange
import com.letta.mobile.data.schedules.HistorySummary
import com.letta.mobile.data.schedules.ScheduleCalendar
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormDraft
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.SchedulesPageModel
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.schedules.ScheduleCalendarActions
import com.letta.mobile.ui.schedules.ScheduleCalendarParams
import com.letta.mobile.ui.schedules.ScheduleRail
import com.letta.mobile.ui.schedules.ScheduleRailParams
import com.letta.mobile.ui.schedules.ScheduleRailState
import com.letta.mobile.ui.schedules.ScheduleRailText
import com.letta.mobile.ui.schedules.CalendarDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/** What the page was given: the host's state, its actions and options, and the schedules projected from them. */
internal class SchedulesPageInput(
    val state: SchedulesPageState,
    val actions: SchedulesPageActions,
    val options: SchedulesPageOptions,
    val data: SchedulesData,
)

/** The schedules projected for this frame: every def, lookups by id, the clock and the run history. */
internal class SchedulesData(
    val defs: List<ScheduleDef>,
    val defsById: Map<String, ScheduleDef>,
    val clock: ScheduleClock,
    val history: HistorySummary,
    /** Each schedule's recurrence in words ("Every weekday at 9:00 AM"). */
    val cadenceById: Map<String, String>,
    val agentNames: Map<String, String>,
) {
    val hasPaused: Boolean get() = defs.any { !it.active }
}

@Composable
internal fun rememberSchedulesData(state: SchedulesPageState, clock: ScheduleClock): SchedulesData {
    // The library's own cron tasks (its cron fallback) join any the host passes, so a cron-backed
    // server's schedules reach the page.
    val crons = remember(state.crons, state.library.crons) { (state.crons + state.library.crons).distinctBy { it.id } }
    // `now` is intentionally not a key: one-shot crons resolve to a single instant when projected,
    // and re-resolving every tick would jump a just-fired one-shot a year ahead.
    val defs = remember(crons, state.library.schedules, state.focusedAgentId, clock.zone) {
        SchedulesPageModel.defs(crons, state.library.schedules, state.focusedAgentId, clock)
    }
    val defsById = remember(defs) { defs.associateBy { it.id } }
    val cadence = remember(defs, clock.zone) { defs.associate { it.id to ScheduleRailText.cadence(it, clock.zone) } }
    val agentNames = remember(state.library.agents) { state.library.agents.associate { it.id.value to it.name } }
    val history = remember(defs, clock) { ScheduleProjection.history(defs, clock.now, clock.zone) }
    return SchedulesData(defs, defsById, clock, history, cadence, agentNames)
}

/** What the main content draws and reports. */
internal class SchedulesBody(
    val state: SchedulesPageState,
    val data: SchedulesData,
    val view: SchedulesView,
    val calendar: ScheduleCalendarParams,
    val selectedId: String?,
    val onRefresh: () -> Unit,
)

/** The page state that outlives a day change: the view (saveable), the rail, the open form / confirm, the paused filter. */
internal class SchedulesPageStates(
    val view: MutableState<SchedulesView?>,
    val rail: MutableState<ScheduleRailState>,
    val form: MutableState<ScheduleFormDraft?>,
    val pendingDelete: MutableState<String?>,
    val showPaused: MutableState<Boolean>,
)

/** The page's own UI state: the view, the rail, the day the calendar is anchored on and the open form / confirm. */
@Stable
internal class SchedulesPageUi(states: SchedulesPageStates, val today: LocalDate) {
    private var chosenView by states.view
    var rail by states.rail
    var form by states.form
    var pendingDelete by states.pendingDelete
    var showPaused by states.showPaused

    /** The day the calendar shows: its week, its month, or the agenda's first day. */
    var anchor by mutableStateOf(today)

    /** The chosen view, else Week on a wide page and Agenda on a phone. */
    fun view(wide: Boolean): SchedulesView = chosenView ?: if (wide) SchedulesView.Week else SchedulesView.Agenda

    fun selectView(view: SchedulesView) {
        chosenView = view
    }

    fun shift(view: SchedulesView, steps: Int) {
        anchor = CalendarRange.shift(view, anchor, steps)
    }

    /** Rail and calendar callbacks; an empty slot starts a draft only when the page can create. */
    fun calendarActions(canCreate: Boolean) = ScheduleCalendarActions(
        onRunClick = { rail = ScheduleRailState.Run(it) },
        onScheduleClick = { rail = ScheduleRailState.Detail(it) },
        onSelectDate = { anchor = it },
        onOpenDay = {
            anchor = it
            chosenView = SchedulesView.Week
        },
        onEmptySlot = if (canCreate) { date, hour -> form = SchedulesPageModel.draftAt(date, hour) } else null,
    )
}

/** The view survives configuration changes; the anchor follows today; the rail, dialogs and filter persist. */
@Composable
internal fun rememberSchedulesPageUi(clock: ScheduleClock, initialView: SchedulesView?): SchedulesPageUi {
    val view = rememberSaveable { mutableStateOf(initialView) }
    val states = remember {
        SchedulesPageStates(
            view = view,
            rail = mutableStateOf(ScheduleRailState.Overview),
            form = mutableStateOf(null),
            pendingDelete = mutableStateOf(null),
            showPaused = mutableStateOf(true),
        )
    }
    val today = remember(clock) { clock.now.toLocalDateTime(clock.zone).date }
    return remember(today) { SchedulesPageUi(states, today) }
}

/** The calendar days the current view spans, re-projected when the clock ticks (every 30 s). */
@Composable
internal fun rememberCalendarDays(ui: SchedulesPageUi, view: SchedulesView, data: SchedulesData): List<CalendarDay> {
    val anchor = ui.anchor
    val showPaused = ui.showPaused
    return remember(data.defs, view, anchor, data.clock, showPaused) {
        if (!view.hasRange) {
            emptyList()
        } else {
            ScheduleCalendar.days(data.defs, CalendarRange.dates(view, anchor), CalendarQuery(data.clock, includePaused = showPaused))
        }
    }
}

@Composable
internal fun SchedulesPageLayout(ui: SchedulesPageUi, input: SchedulesPageInput, modifier: Modifier) {
    val data = input.data
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(SchedulesPageTags.PAGE)) {
        val wide = maxWidth >= LettaDimens.Pane.wideBreakpoint
        val docked = maxWidth >= CalendarDimens.railDockBreakpoint
        val view = ui.view(wide)
        val days = rememberCalendarDays(ui, view, data)
        val actions = ui.calendarActions(input.state.canCreate)
        val calendar = ScheduleCalendarParams(days, ui.today, ui.anchor, data.clock, data.cadenceById, actions)
        Column(Modifier.fillMaxSize()) {
            SchedulesHeader(headerParams(ui, input, view, wide))
            SchedulesFilters(filterParams(ui, input))
            val selectedId = (ui.rail as? ScheduleRailState.Detail)?.scheduleId
            val body = SchedulesBody(input.state, data, view, calendar, selectedId, input.actions.onRefresh)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                SchedulesMainContent(body, Modifier.weight(1f).fillMaxHeight())
                if (docked && (ui.rail !is ScheduleRailState.Overview || view.docksOverview)) ScheduleRail(railParams(ui, input))
            }
            if (!wide) SchedulesViewBar(view, ui::selectView)
        }
        if (!docked && ui.rail !is ScheduleRailState.Overview) {
            LettaPane(LettaPaneKind.Sheet, onDismiss = { ui.rail = ScheduleRailState.Overview }, testTag = SchedulesPageTags.DETAIL_SHEET) {
                ScheduleRail(railParams(ui, input), Modifier.fillMaxWidth().height(DetailSheetHeight))
            }
        }
    }
}

private fun headerParams(ui: SchedulesPageUi, input: SchedulesPageInput, view: SchedulesView, wide: Boolean) = SchedulesHeaderParams(
    view = view,
    onView = ui::selectView,
    range = SchedulesRange(
        label = CalendarRange.label(view, ui.anchor, compact = !wide),
        onShift = { ui.shift(view, it) },
        onToday = { ui.anchor = ui.today },
        zone = CalendarLabels.zoneLabel(input.data.clock.zone, input.data.clock.now),
    ),
    onNew = { ui.form = ScheduleFormDraft() }.takeIf { input.state.canCreate },
    options = input.options,
    refresh = SchedulesRefresh(input.state.library.isLoading, input.actions.onRefresh),
    wide = wide,
)

private fun filterParams(ui: SchedulesPageUi, input: SchedulesPageInput) = SchedulesFilterParams(
    state = input.state,
    onSelectAgent = input.actions.onSelectAgent,
    showPaused = ui.showPaused.takeIf { input.data.hasPaused },
    onTogglePaused = { ui.showPaused = !ui.showPaused },
)

private fun railParams(ui: SchedulesPageUi, input: SchedulesPageInput) = ScheduleRailParams(
    rail = ui.rail,
    defs = input.data.defs,
    defsById = input.data.defsById,
    history = input.data.history,
    now = input.data.clock.now,
    zone = input.data.clock.zone,
    onSelectSchedule = { ui.rail = ScheduleRailState.Detail(it) },
    onBackToOverview = { ui.rail = ScheduleRailState.Overview },
    onDelete = { ui.pendingDelete = it },
    onTogglePause = input.actions.onTogglePause,
    agentNames = input.data.agentNames,
)

/** The New-schedule form and the delete confirmation, when open. */
@Composable
internal fun SchedulesPageDialogs(ui: SchedulesPageUi, state: SchedulesPageState, actions: SchedulesPageActions, data: SchedulesData) {
    val clock = data.clock
    ui.form?.let { initial ->
        ScheduleFormPane(
            clock = clock,
            initial = initial,
            onDismiss = { ui.form = null },
            onSubmit = { draft ->
                draft.toRequest(state.targetAgentId, clock.zone)?.let(actions.onCreate)
                ui.form = null
            },
        )
    }
    ui.pendingDelete?.let { id ->
        LettaConfirmPane(
            request = LettaConfirmRequest(
                title = "Delete schedule",
                message = "Delete “${data.defsById[id]?.name ?: id}”? It will stop running.",
                confirmLabel = "Delete",
            ),
            onConfirm = {
                actions.delete(state.deleteTarget(id))
                ui.pendingDelete = null
                ui.rail = ScheduleRailState.Overview
            },
            onDismiss = { ui.pendingDelete = null },
        )
    }
}

private val DetailSheetHeight = 520.dp

/** Views that keep the overview rail docked on a wide page. */
private val SchedulesView.docksOverview: Boolean
    get() = this != SchedulesView.History
