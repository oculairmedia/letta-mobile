package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.SchedulesPageModel
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.schedules.CalendarDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlin.jvm.JvmInline
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 *  (hard cut S14): the Schedules page shared by desktop, Android and web.
 *
 * A calendar of every schedule the host knows (native schedule-admin schedules and cron tasks,
 * merged by [SchedulesPageModel.defs]): Agenda, Week and Month views of upcoming and past runs, a
 * List of every definition and the run History. Times read in the viewer's zone, which the header
 * names. At or above [LettaDimens.Pane.wideBreakpoint] the view switcher sits in the header and Week
 * opens first; below it Agenda opens first and the switcher is a bar at the bottom, in thumb reach.
 * From [CalendarDimens.railDockBreakpoint] the rail docks beside the calendar; below it a schedule's
 * or a run's detail opens in a sheet. "New schedule" (or tapping an empty hour) opens the cron
 * builder on a [LettaPane]; deleting asks first.
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
    val ui = rememberSchedulesPageUi(clock, options.initialView)
    val data = rememberSchedulesData(state, clock)
    SchedulesPageLayout(ui, SchedulesPageInput(state, actions, options, data), modifier)
    SchedulesPageDialogs(ui, state, actions, data)
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

/**
 * What the page asks its host to do. A null [onTogglePause] disables Pause / Resume.
 *
 * Deletes are typed by the list that rendered the row: [onDeleteCron] for a
 * cron task, [onDeleteSchedule] for a native schedule with the agent it belongs to, so a host never
 * has to guess the backend from a bare id.
 */
@Immutable
data class SchedulesPageActions(
    val onRefresh: () -> Unit = {},
    val onSelectAgent: (String) -> Unit = {},
    val onCreate: (ScheduleCreateRequest) -> Unit = {},
    val onDeleteSchedule: (NativeScheduleRef) -> Unit = {},
    val onDeleteCron: (CronTaskId) -> Unit = {},
    val onTogglePause: ((String, Boolean) -> Unit)? = null,
)

/** A cron task's id, as the host's cron API names it. */
@JvmInline
value class CronTaskId(val value: String)

/** A native schedule and the agent that owns it. */
@Immutable
data class NativeScheduleRef(val agentId: String, val scheduleId: String)

/** The row a delete confirmation was opened from; null when it is no longer listed. */
internal sealed interface ScheduleDeleteTarget {
    data class Cron(val id: CronTaskId) : ScheduleDeleteTarget

    data class Native(val ref: NativeScheduleRef) : ScheduleDeleteTarget
}

/**
 * Resolves [id] the way [SchedulesPageModel.defs] rendered it: a cron task in scope for the focused
 * agent wins over a native schedule with the same id, exactly as the merged list shows it.
 */
internal fun SchedulesPageState.deleteTarget(id: String): ScheduleDeleteTarget? {
    val cron = (crons + library.crons).firstOrNull {
        it.id == id && (focusedAgentId == null || it.agentId == null || it.agentId == focusedAgentId)
    }
    if (cron != null) return ScheduleDeleteTarget.Cron(CronTaskId(cron.id))
    val native = library.schedules.firstOrNull { it.id == id } ?: return null
    return ScheduleDeleteTarget.Native(NativeScheduleRef(native.agentId, native.id))
}

internal fun SchedulesPageActions.delete(target: ScheduleDeleteTarget?) {
    when (target) {
        is ScheduleDeleteTarget.Cron -> onDeleteCron(target.id)
        is ScheduleDeleteTarget.Native -> onDeleteSchedule(target.ref)
        null -> Unit
    }
}

/** Host presentation: [showTitle] false when the host already titles the screen; [touch] sizes chips for fingers. */
@Immutable
data class SchedulesPageOptions(
    val showTitle: Boolean = true,
    val touch: Boolean = false,
    /** The view the page opens on; null picks by width (Week on a wide page, Agenda on a phone). */
    val initialView: SchedulesView? = null,
    /** A clock that does not tick (tests and previews); null follows the system clock. */
    val fixedClock: ScheduleClock? = null,
)

/** Test tags for the shared Schedules page. */
object SchedulesPageTags {
    const val PAGE = "schedules_page"
    const val NEW = "schedules_new"
    /** Pages the calendar back / forward by the view's range (a week, a month, an agenda page). */
    const val PREVIOUS_WEEK = "schedules_previous_week"
    const val NEXT_WEEK = "schedules_next_week"
    const val RANGE = "schedules_range"
    const val TODAY = "schedules_today"
    const val ZONE = "schedules_zone"
    const val VIEW_BAR = "schedules_view_bar"
    const val PAUSED_FILTER = "schedules_paused_filter"
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
