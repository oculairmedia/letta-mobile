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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.HistorySummary
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.SchedulesPageModel
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.schedules.ScheduleRail
import com.letta.mobile.ui.schedules.ScheduleRailParams
import com.letta.mobile.ui.schedules.ScheduleRailState
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-ckmub.3.16 (hard cut S14): the Schedules page shared by desktop, Android and web.
 *
 * Week / Agenda / Timeline / History / Rules views over every schedule the host knows (native
 * schedule-admin schedules and cron tasks, merged by [SchedulesPageModel.defs]). At or above
 * [LettaDimens.Pane.wideBreakpoint] the rail docks beside the view (overview, a schedule's detail or a
 * run's detail); below it a selected schedule or run opens in a sheet. "New schedule" opens the cron
 * builder form on a [LettaPane]; deleting asks first.
 *
 * Hosts own the data: sharedLogic's ScheduleLibraryController (plus, on desktop, its cron panel).
 */
@Composable
fun SchedulesPage(
    state: SchedulesPageState,
    actions: SchedulesPageActions,
    modifier: Modifier = Modifier,
    options: SchedulesPageOptions = SchedulesPageOptions(),
) {
    val clock = options.fixedClock ?: rememberTickingClock()
    FocusedAgentSelection(state, actions)
    // `now` is intentionally not a key: one-shot crons resolve to a single instant when projected,
    // and re-resolving every tick would jump a just-fired one-shot a year ahead.
    val defs = remember(state.crons, state.library.schedules, state.focusedAgentId, clock.zone) {
        SchedulesPageModel.defs(state.crons, state.library.schedules, state.focusedAgentId, clock)
    }
    val ui = rememberSchedulesPageUi(clock, options.initialView)
    val defsById = remember(defs) { defs.associateBy { it.id } }
    val railParams = ScheduleRailParams(
        rail = ui.rail,
        defs = defs,
        defsById = defsById,
        history = remember(defs, clock) { ScheduleProjection.history(defs, clock.now, clock.zone) },
        now = clock.now,
        zone = clock.zone,
        onSelectSchedule = { ui.rail = ScheduleRailState.Detail(it) },
        onBackToOverview = { ui.rail = ScheduleRailState.Overview },
        onDelete = { ui.pendingDelete = it },
        onTogglePause = actions.onTogglePause,
    )
    SchedulesPageLayout(
        ui = ui,
        header = ui.headerParams(state.canCreate, options),
        body = ui.body(state, defs, clock, railParams.history, actions.onRefresh),
        railParams = railParams,
        onSelectAgent = actions.onSelectAgent,
        modifier = modifier,
    )
    SchedulesPageDialogs(ui, state, actions, clock, defsById)
}

/** The page state that outlives a day change: the view (saveable), the rail and the open form / confirm. */
private class SchedulesPageStates(
    val view: MutableState<SchedulesView>,
    val rail: MutableState<ScheduleRailState>,
    val creating: MutableState<Boolean>,
    val pendingDelete: MutableState<String?>,
)

/** The page's own UI state: the view, the rail, the dates in view and the open form / confirm. */
@Stable
private class SchedulesPageUi(states: SchedulesPageStates, val today: LocalDate) {
    var view by states.view
    var rail by states.rail
    var creating by states.creating
    var pendingDelete by states.pendingDelete
    var selectedDate by mutableStateOf(today)
    var weekStart by mutableStateOf(ScheduleProjection.mondayOf(today))

    /** Week / Agenda / Rules keep the overview docked; Timeline and History go full width until a pick. */
    val showsDockedRail: Boolean get() = rail !is ScheduleRailState.Overview || view.docksOverview

    fun headerParams(canCreate: Boolean, options: SchedulesPageOptions) = SchedulesHeaderParams(
        view = view,
        onView = { view = it },
        rangeLabel = SchedulesPageModel.weekRangeLabel(weekStart),
        onShiftWeek = { weeks -> weekStart = weekStart.plus(weeks * DAYS_PER_WEEK, DateTimeUnit.DAY) },
        onNew = { creating = true }.takeIf { canCreate },
        options = options,
    )

    fun body(
        state: SchedulesPageState,
        defs: List<ScheduleDef>,
        clock: ScheduleClock,
        history: HistorySummary,
        onRefresh: () -> Unit,
    ) = SchedulesBody(
        state = state,
        defs = defs,
        view = view,
        clock = clock,
        dates = SchedulesDates(today = today, selected = selectedDate, weekStart = weekStart),
        selectedId = (rail as? ScheduleRailState.Detail)?.scheduleId,
        history = history,
        onRefresh = onRefresh,
        onSelectDate = { selectedDate = it },
        onSelectRun = { rail = ScheduleRailState.Run(it) },
        onSelectSchedule = { rail = ScheduleRailState.Detail(it) },
    )
}

/** The view survives configuration changes; the dates follow today; the rail and dialogs persist. */
@Composable
private fun rememberSchedulesPageUi(clock: ScheduleClock, initialView: SchedulesView): SchedulesPageUi {
    val view = rememberSaveable { mutableStateOf(initialView) }
    val states = remember {
        SchedulesPageStates(
            view = view,
            rail = mutableStateOf(ScheduleRailState.Overview),
            creating = mutableStateOf(false),
            pendingDelete = mutableStateOf(null),
        )
    }
    val today = remember(clock) { clock.now.toLocalDateTime(clock.zone).date }
    return remember(today) { SchedulesPageUi(states, today) }
}

@Composable
private fun SchedulesPageLayout(
    ui: SchedulesPageUi,
    header: SchedulesHeaderParams,
    body: SchedulesBody,
    railParams: ScheduleRailParams,
    onSelectAgent: (String) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(SchedulesPageTags.PAGE)) {
        val wide = maxWidth >= LettaDimens.Pane.wideBreakpoint
        Column(Modifier.fillMaxSize()) {
            SchedulesHeader(header)
            SchedulesAgentChips(body.state, onSelectAgent)
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    SchedulesMainContent(body, Modifier.weight(1f).fillMaxHeight())
                    if (ui.showsDockedRail) ScheduleRail(railParams)
                }
            } else {
                SchedulesMainContent(body, Modifier.fillMaxSize())
            }
        }
        if (!wide && ui.rail !is ScheduleRailState.Overview) {
            LettaPane(LettaPaneKind.Sheet, onDismiss = { ui.rail = ScheduleRailState.Overview }, testTag = SchedulesPageTags.DETAIL_SHEET) {
                ScheduleRail(railParams, Modifier.fillMaxWidth().height(DetailSheetHeight))
            }
        }
    }
}

/** The New-schedule form and the delete confirmation, when open. */
@Composable
private fun SchedulesPageDialogs(
    ui: SchedulesPageUi,
    state: SchedulesPageState,
    actions: SchedulesPageActions,
    clock: ScheduleClock,
    defsById: Map<String, ScheduleDef>,
) {
    if (ui.creating) {
        ScheduleFormPane(
            clock = clock,
            onDismiss = { ui.creating = false },
            onSubmit = { draft ->
                draft.toRequest(state.targetAgentId, clock.zone)?.let(actions.onCreate)
                ui.creating = false
            },
        )
    }
    ui.pendingDelete?.let { id ->
        LettaConfirmPane(
            request = LettaConfirmRequest(
                title = "Delete schedule",
                message = "Delete “${defsById[id]?.name ?: id}”? It will stop running.",
                confirmLabel = "Delete",
            ),
            onConfirm = {
                actions.onDelete(id)
                ui.pendingDelete = null
                ui.rail = ScheduleRailState.Overview
            },
            onDismiss = { ui.pendingDelete = null },
        )
    }
}

/** Everything the page shows. [crons] are cron tasks the host loads beside the library's own. */
@Immutable
data class SchedulesPageState(
    val library: ScheduleLibraryState = ScheduleLibraryState(isLoading = false),
    val crons: List<CronTask> = emptyList(),
    /** The agent the host is focused on: the page loads and shows only its schedules. */
    val focusedAgentId: String? = null,
    /** False hides "New schedule" (no backend route, or no agent to schedule for). */
    val canCreate: Boolean = false,
) {
    /** The agent a new schedule is for: the focused one, else the library's selection. */
    val targetAgentId: String? get() = focusedAgentId ?: library.selectedAgentId

    /** The agent chips show only when the host has no focused agent and there is a choice. */
    val showsAgentChips: Boolean get() = focusedAgentId == null && library.agents.size > 1
}

/** What the page asks its host to do. A null [onTogglePause] disables Pause / Resume. */
@Immutable
data class SchedulesPageActions(
    val onRefresh: () -> Unit = {},
    val onSelectAgent: (String) -> Unit = {},
    val onCreate: (ScheduleCreateRequest) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onTogglePause: ((String, Boolean) -> Unit)? = null,
)

/** Host presentation: [showTitle] false when the host already titles the screen; [touch] sizes chips for fingers. */
@Immutable
data class SchedulesPageOptions(
    val showTitle: Boolean = true,
    val touch: Boolean = false,
    val initialView: SchedulesView = SchedulesView.Week,
    /** A clock that does not tick (tests and previews); null follows the system clock. */
    val fixedClock: ScheduleClock? = null,
)

/** Test tags for the shared Schedules page. */
object SchedulesPageTags {
    const val PAGE = "schedules_page"
    const val NEW = "schedules_new"
    const val PREVIOUS_WEEK = "schedules_previous_week"
    const val NEXT_WEEK = "schedules_next_week"
    const val RANGE = "schedules_range"
    const val EMPTY = "schedules_empty"
    const val ERROR = "schedules_error"
    const val RULES = "schedules_rules"
    const val DETAIL_SHEET = "schedules_detail_sheet"
    const val FORM = "schedules_form"
    const val FORM_NAME = "schedules_form_name"
    const val FORM_PROMPT = "schedules_form_prompt"
    const val FORM_PREVIEW = "schedules_form_preview"
    const val FORM_SUBMIT = "schedules_form_submit"

    fun tab(view: SchedulesView): String = "schedules_tab_${view.name.lowercase()}"

    fun view(view: SchedulesView): String = "schedules_view_${view.name.lowercase()}"

    /** One schedule's row in the Rules view. */
    fun rule(scheduleId: String): String = RosterListTag(RULES).row("$RULES_SECTION:$scheduleId").value

    internal const val RULES_SECTION = "rules"

    fun agent(agentId: String): String = "schedules_agent_$agentId"

    fun cadence(label: String): String = "schedules_cadence_${label.lowercase().replace(' ', '_')}"
}

private val DetailSheetHeight = 520.dp

/** Views that keep the overview rail docked on a wide page. */
private val SchedulesView.docksOverview: Boolean
    get() = this == SchedulesView.Week || this == SchedulesView.Agenda || this == SchedulesView.Rules

/** Native schedules load per agent: when the host focuses one, select it so its schedules load. */
@Composable
private fun FocusedAgentSelection(state: SchedulesPageState, actions: SchedulesPageActions) {
    val focused = state.focusedAgentId
    LaunchedEffect(focused, state.library.selectedAgentId) {
        if (!focused.isNullOrBlank() && focused != state.library.selectedAgentId) actions.onSelectAgent(focused)
    }
}

/** The page's clock, advanced every [NOW_TICK] so countdowns and the now line stay live. */
@Composable
private fun rememberTickingClock(): ScheduleClock {
    val zone = remember { TimeZone.currentSystemDefault() }
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_TICK)
            now = Clock.System.now()
        }
    }
    return remember(now, zone) { ScheduleClock(now, zone) }
}

private val NOW_TICK = 30.seconds
private const val DAYS_PER_WEEK = 7
