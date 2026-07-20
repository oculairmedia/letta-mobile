package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.letta.mobile.desktop.components.DesktopChipTab
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

import kotlin.time.Duration.Companion.milliseconds
/** The four schedule views (Penpot "Desktop · Schedules (week/timeline)"). */
internal enum class ScheduleView(val label: String) {
    Week("Week"),
    Agenda("Agenda"),
    Timeline("Timeline"),
    History("History"),
}

internal sealed interface RailState {
    data object Overview : RailState
    data class Detail(val scheduleId: String) : RailState
    data class Run(val run: ScheduleRun) : RailState
}

internal val RAIL_WIDTH = 372.dp
internal val HOUR_HEIGHT = 44.dp
internal const val GRID_HOURS = 24
internal const val HOUR_LABEL_STEP = 3
internal const val WEEK_GRID_MAX_PER_DAY = 4
internal const val NOW_TICK_MILLIS = 30_000L

/**
 * Phase-7 Schedules surface, rebuilt to the Penpot mockups: a Week / Agenda /
 * Timeline / History tab switcher with a persistent right rail (OVERVIEW stats
 * + active-schedule list, switching to a master-detail / run-detail on click),
 * and a centered "New schedule" modal. All view data is computed by the shared
 * [ScheduleProjection]; this file is binding-only.
 */
@Composable
fun DesktopScheduleSurface(
    state: DesktopScheduleLibraryState,
    onRefresh: () -> Unit,
    onAgentSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    crons: List<CronTask> = emptyList(),
    focusedAgentId: String? = null,
    onDeleteCron: (String) -> Unit = {},
    canCreate: Boolean = false,
    onCreateCron: (agentId: String?, name: String, prompt: String, cron: String, recurring: Boolean, timezone: String) -> Unit =
        { _, _, _, _, _, _ -> },
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    // Keep `now` advancing so countdown labels, the now-line, and the
    // Running/Next classification stay live while the screen is open instead of
    // freezing at first composition (Codex/CodeRabbit review #2). A 30s tick is
    // enough for minute-granularity labels; the projections it keys are cheap.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(NOW_TICK_MILLIS.milliseconds)
            now = Clock.System.now()
        }
    }
    val today = remember(now, zone) { now.toLocalDateTime(zone).date }

    val filterAgentId = focusedAgentId
    // Native schedule-admin schedules are loaded per-agent by the controller
    // (only for state.selectedAgentId). When the surface is focused on a
    // specific agent, drive that selection so we load and show that agent's
    // schedules instead of filtering the previously-loaded agent's to empty
    // (Codex review). The agent chips were removed, so this is the only switch.
    LaunchedEffect(focusedAgentId, state.selectedAgentId) {
        if (!focusedAgentId.isNullOrBlank() && focusedAgentId != state.selectedAgentId) {
            onAgentSelected(focusedAgentId)
        }
    }
    val filteredCrons = remember(crons, filterAgentId) {
        if (filterAgentId == null) crons else crons.filter { it.agentId == null || it.agentId == filterAgentId }
    }
    // Native schedule-admin schedules (state.schedules) are a parallel source to
    // /v1/crons. Projecting from crons alone hid real schedules on backends that
    // serve the native route but no crons (Codex review #5), so merge both —
    // crons first, then any native schedule not already represented by id. The
    // native-sourced ids are tracked so the rail can route Delete correctly
    // (native schedules aren't deletable through the cron API).
    val nativeSchedules = state.schedules
    val defs = remember(filteredCrons, nativeSchedules, filterAgentId, zone) {
        // `now` is read but intentionally NOT a key: one-shot crons resolve to a
        // single instant at build time and must stay fixed (re-resolving each
        // tick would jump a just-fired one-shot to next year).
        val cronDefs = ScheduleProjection.toScheduleDefs(filteredCrons, zone, now)
        val cronIds = cronDefs.mapTo(HashSet()) { it.id }
        // Native schedules are already agent-scoped by the controller; don't
        // re-filter by focusedAgentId (that empties them when selection lags).
        val nativeDefs = ScheduleProjection.toScheduleDefsFromNative(nativeSchedules, zone)
            .filter { it.id !in cronIds }
        cronDefs + nativeDefs
    }
    val defsById = remember(defs) { defs.associateBy { it.id } }
    val historySummary = remember(defs, now) { ScheduleProjection.history(defs, now, zone) }

    var view by remember { mutableStateOf(ScheduleView.Week) }
    var rail by remember { mutableStateOf<RailState>(RailState.Overview) }
    var selectedDate by remember(today) { mutableStateOf(today) }
    var weekStart by remember(today) { mutableStateOf(ScheduleProjection.mondayOf(today)) }
    var showCreate by remember { mutableStateOf(false) }

    val weekRange = remember(weekStart) {
        // "June 22 – 28, 2026" (don't repeat the month when the week stays in it).
        val end = weekStart.plus(6, DateTimeUnit.DAY)
        val month = fullMonth(weekStart.month.ordinal + 1)
        if (end.month == weekStart.month) {
            "$month ${weekStart.day} – ${end.day}, ${weekStart.year}"
        } else {
            "$month ${weekStart.day} – ${fullMonth(end.month.ordinal + 1)} ${end.day}, ${end.year}"
        }
    }
    val selectedId = (rail as? RailState.Detail)?.scheduleId

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            ScheduleHeader(
                view = view,
                onView = { view = it },
                rangeLabel = weekRange,
                showRange = view == ScheduleView.Week || view == ScheduleView.Timeline,
                onPrev = { weekStart = weekStart.minus(7, DateTimeUnit.DAY) },
                onNext = { weekStart = weekStart.plus(7, DateTimeUnit.DAY) },
                canCreate = canCreate,
                onNew = { showCreate = true },
            )
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // Local copy: errorMessage is a cross-module property, so it
                    // can't smart-cast inline in the when below.
                    val scheduleError = state.errorMessage
                    when {
                        // Show data as soon as anything projects, even while a
                        // parallel source is still loading.
                        defs.isNotEmpty() -> when (view) {
                            ScheduleView.Week -> WeekView(
                                WeekViewParams(
                                    defs = defs,
                                    weekStart = weekStart,
                                    today = today,
                                    now = now,
                                    zone = zone,
                                    selectedId = selectedId,
                                    onRunClick = { rail = RailState.Run(it) },
                                ),
                            )
                            ScheduleView.Agenda -> AgendaView(
                                AgendaViewParams(
                                    defs = defs,
                                    selectedDate = selectedDate,
                                    today = today,
                                    now = now,
                                    zone = zone,
                                    onSelectDate = { selectedDate = it },
                                    onRunClick = { rail = RailState.Run(it) },
                                ),
                            )
                            ScheduleView.Timeline -> TimelineView(
                                TimelineViewParams(
                                    defs = defs,
                                    weekStart = weekStart,
                                    today = today,
                                    now = now,
                                    zone = zone,
                                    onLaneClick = { rail = RailState.Detail(it) },
                                ),
                            )
                            ScheduleView.History -> HistoryView(historySummary)
                        }
                        // Surface backend failures with a retry instead of
                        // masking them as "No schedules yet" (Codex review).
                        scheduleError != null -> Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
                            DesktopInlineError(message = scheduleError, onRetry = onRefresh, retrying = state.isLoading)
                        }
                        state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Loading schedules…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
                        }
                        else -> ScheduleEmptyState(canCreate)
                    }
                }
                // Week/Agenda always show the rail (Overview or detail). Timeline
                // and History are full-width like the mockups, unless a schedule
                // is selected (then the detail panel appears).
                val showRail = rail !is RailState.Overview || view == ScheduleView.Week || view == ScheduleView.Agenda
                if (showRail) {
                    ScheduleRail(
                        ScheduleRailParams(
                            rail = rail,
                            defs = defs,
                            defsById = defsById,
                            history = historySummary,
                            now = now,
                            zone = zone,
                            onSelectSchedule = { rail = RailState.Detail(it) },
                            onBackToOverview = { rail = RailState.Overview },
                            onDelete = { onDeleteCron(it); rail = RailState.Overview },
                        ),
                    )
                }
            }
        }

        if (showCreate) {
            CreateScheduleModal(
                CreateScheduleModalParams(
                    clock = CreateScheduleClock(now = now, zone = zone),
                    canCreate = canCreate,
                    onDismiss = { showCreate = false },
                    onCreate = { name, prompt, cron ->
                        onCreateCron(filterAgentId, name, prompt, cron, true, zone.id)
                        showCreate = false
                    },
                ),
            )
        }
    }
}

// --- Header -----------------------------------------------------------------

@Composable
internal fun ScheduleHeader(
    view: ScheduleView,
    onView: (ScheduleView) -> Unit,
    rangeLabel: String,
    showRange: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    canCreate: Boolean,
    onNew: () -> Unit,
) {
    // Title on its own row; the view tabs + date-nav sit BENEATH it — matching
    // Memory/Skills/Channels so every page reads the same way.
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Schedules", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (canCreate) {
                DesktopDefaultButton(onClick = onNew) {
                    DesktopButtonContent(text = "New schedule", icon = Icons.Outlined.Add)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScheduleView.entries.forEach { entry ->
                DesktopChipTab(entry.label, entry == view) { onView(entry) }
            }
            Spacer(Modifier.weight(1f))
            // Always rendered (invisible when not applicable) so switching tabs
            // never shifts the layout.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (showRange) 1f else 0f)) {
                IconBtn(Icons.Outlined.ChevronLeft, "Previous", onPrev)
                Text(rangeLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
                IconBtn(Icons.Outlined.ChevronRight, "Next", onNext)
            }
        }
    }
}
