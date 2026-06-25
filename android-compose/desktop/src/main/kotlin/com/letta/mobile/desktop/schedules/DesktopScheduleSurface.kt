package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import com.letta.mobile.desktop.components.DesktopChipTab
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.WeekCalendar
import com.kizitonwose.calendar.compose.weekcalendar.rememberWeekCalendarState
import com.kizitonwose.calendar.core.WeekDay
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.letta.mobile.data.schedules.CronBuilder
import com.letta.mobile.data.schedules.CronBuilderState
import com.letta.mobile.data.schedules.CronCadence
import com.letta.mobile.data.schedules.CronSchedule
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** The four schedule views (Penpot "Desktop · Schedules (week/timeline)"). */
private enum class ScheduleView(val label: String) {
    Week("Week"),
    Agenda("Agenda"),
    Timeline("Timeline"),
    History("History"),
}

private sealed interface RailState {
    data object Overview : RailState
    data class Detail(val scheduleId: String) : RailState
    data class Run(val run: ScheduleRun) : RailState
}

private val RAIL_WIDTH = 372.dp
private val HOUR_HEIGHT = 44.dp
private const val GRID_HOURS = 24
private const val HOUR_LABEL_STEP = 3
private const val WEEK_GRID_MAX_PER_DAY = 4
private const val NOW_TICK_MILLIS = 30_000L

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
            kotlinx.coroutines.delay(NOW_TICK_MILLIS)
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
    val (defs, nativeDefIds) = remember(filteredCrons, nativeSchedules, filterAgentId, zone) {
        // `now` is read but intentionally NOT a key: one-shot crons resolve to a
        // single instant at build time and must stay fixed (re-resolving each
        // tick would jump a just-fired one-shot to next year).
        val cronDefs = ScheduleProjection.toScheduleDefs(filteredCrons, zone, now)
        val cronIds = cronDefs.mapTo(HashSet()) { it.id }
        // Native schedules are already agent-scoped by the controller; don't
        // re-filter by focusedAgentId (that empties them when selection lags).
        val nativeDefs = ScheduleProjection.toScheduleDefsFromNative(nativeSchedules, zone)
            .filter { it.id !in cronIds }
        (cronDefs + nativeDefs) to nativeDefs.mapTo(HashSet()) { it.id }
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
                            ScheduleView.Week -> WeekView(defs, weekStart, today, now, zone, selectedId) { rail = RailState.Run(it) }
                            ScheduleView.Agenda -> AgendaView(defs, selectedDate, today, now, zone, { selectedDate = it }) { rail = RailState.Run(it) }
                            ScheduleView.Timeline -> TimelineView(defs, weekStart, today, now, zone) { rail = RailState.Detail(it) }
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
                        rail = rail,
                        defs = defs,
                        defsById = defsById,
                        history = historySummary,
                        now = now,
                        zone = zone,
                        nativeDefIds = nativeDefIds,
                        onSelectSchedule = { rail = RailState.Detail(it) },
                        onBackToOverview = { rail = RailState.Overview },
                        onDelete = { onDeleteCron(it); rail = RailState.Overview },
                    )
                }
            }
        }

        if (showCreate) {
            CreateScheduleModal(
                now = now,
                zone = zone,
                canCreate = canCreate,
                onDismiss = { showCreate = false },
                onCreate = { name, prompt, cron ->
                    onCreateCron(filterAgentId, name, prompt, cron, true, zone.id)
                    showCreate = false
                },
            )
        }
    }
}

// --- Header -----------------------------------------------------------------

@Composable
private fun ScheduleHeader(
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
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onNew)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New schedule", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
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

// --- Week time-grid ---------------------------------------------------------

@Composable
private fun WeekView(
    defs: List<ScheduleDef>,
    weekStart: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: TimeZone,
    selectedId: String?,
    onRunClick: (ScheduleRun) -> Unit,
) {
    val grid = remember(defs, weekStart, now) { ScheduleProjection.week(defs, weekStart, now, zone) }
    val nowMinutes = remember(now, zone) { now.toLocalDateTime(zone).let { it.hour * 60 + it.minute } }
    val todayInWeek = today in grid.days
    val gutter = 56.dp

    // The week grid is for discrete daily/weekly schedules. Sub-daily schedules
    // (hourly and faster) would fill every row into a wall, so — like the
    // mockup — they're excluded here and surfaced in the rail + Timeline view.
    val frequent = remember(grid) {
        grid.runs.groupBy { it.run.scheduleId }.filterValues { it.size > 7 * WEEK_GRID_MAX_PER_DAY }.keys
    }
    val visibleRuns = remember(grid, frequent) { grid.runs.filter { it.run.scheduleId !in frequent } }
    val hiddenCount = remember(frequent) { frequent.size }

    Column(Modifier.fillMaxSize()) {
        // Day header row.
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp)) {
            grid.days.forEach { date ->
                val isToday = date == today
                Box(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isToday) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(ScheduleFormat.weekdayShort(date.dayOfWeek), style = MaterialTheme.typography.labelMedium, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${date.day}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (visibleRuns.isEmpty() && hiddenCount > 0) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "$hiddenCount high-frequency schedule${if (hiddenCount == 1) "" else "s"} run every hour — see the Timeline view for their run density.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                )
            }
        }
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HOUR_HEIGHT * 5).roundToPx() }) }
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT * GRID_HOURS)) {
                // Hour gutter — label every 3 hours, aligned to the gridline.
                Column(Modifier.width(gutter)) {
                    (0 until GRID_HOURS).forEach { h ->
                        Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                            if (h % HOUR_LABEL_STEP == 0) {
                                Text(
                                    "${ScheduleFormat.pad2(h)}:00",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                                    modifier = Modifier.offset(y = (-6).dp).padding(end = 10.dp),
                                )
                            }
                        }
                    }
                }
                // Day columns.
                grid.days.forEachIndexed { dayIndex, date ->
                    val isToday = date == today
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isToday) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f) else Color.Transparent),
                    ) {
                        // Hour grid lines (stronger every 3 hours).
                        Column(Modifier.fillMaxSize()) {
                            (0 until GRID_HOURS).forEach { h ->
                                Box(
                                    Modifier.height(HOUR_HEIGHT).fillMaxWidth().border(
                                        width = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (h % HOUR_LABEL_STEP == 0) 0.4f else 0.18f),
                                    ),
                                )
                            }
                        }
                        // Left column divider.
                        Box(Modifier.fillMaxHeight().width(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)))
                        // Run bars (discrete daily/weekly schedules only).
                        visibleRuns.filter { it.dayIndex == dayIndex }.forEach { wr ->
                            val emphasized = selectedId == null || wr.run.scheduleId == selectedId
                            WeekRunBar(wr.run, wr.minuteOfDay, emphasized, onClick = { onRunClick(wr.run) })
                        }
                    }
                }
            }
            // Now line across the day columns, with a dot at the gutter edge.
            if (todayInWeek) {
                Row(
                    Modifier.fillMaxWidth().offset(y = HOUR_HEIGHT * (nowMinutes / 60f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(gutter), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.size(8.dp).offset(x = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                    Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
private fun WeekRunBar(run: ScheduleRun, minuteOfDay: Int, emphasized: Boolean, onClick: () -> Unit) {
    val color = statusColor(run.status)
    val filled = run.status == RunStatus.Done || run.status == RunStatus.Running
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp)
            .offset(y = HOUR_HEIGHT * (minuteOfDay / 60f))
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (filled) color.copy(alpha = if (emphasized) 0.9f else 0.4f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (emphasized) 1f else 0.4f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            run.scheduleName,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) MaterialTheme.colorScheme.onSurface else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Agenda -----------------------------------------------------------------

@Composable
private fun AgendaView(
    defs: List<ScheduleDef>,
    selectedDate: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: TimeZone,
    onSelectDate: (LocalDate) -> Unit,
    onRunClick: (ScheduleRun) -> Unit,
) {
    val agenda = remember(defs, selectedDate, now) { ScheduleProjection.agenda(defs, selectedDate, now, zone) }
    val completed = agenda.runs.count { it.status == RunStatus.Done }
    val cadenceById = remember(defs) {
        defs.associate { it.id to (it.cron?.let { c -> CronSchedule.parse(c)?.let(CronSchedule::describe) } ?: "One-time") }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ScheduleFormat.monthShort(selectedDate.month.ordinal + 1)} ${selectedDate.year}".uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
            Spacer(Modifier.weight(1f))
            val isToday = selectedDate == today
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelectDate(today) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AgendaDateStrip(selectedDate, today, onSelectDate)
        Spacer(Modifier.height(16.dp))
        Text(dayHeading(selectedDate, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text("${agenda.runs.size} scheduled runs · $completed completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(12.dp))
        if (agenda.runs.isEmpty()) {
            Text("No runs scheduled for this day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
        } else {
            LazyColumn {
                items(items = agenda.runs, key = { it.scheduleId + it.instant.toString() }) { run ->
                    AgendaRow(run, cadenceById[run.scheduleId], now, zone, onClick = { onRunClick(run) })
                }
            }
        }
    }
}

@Composable
private fun AgendaDateStrip(selectedDate: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val state = rememberWeekCalendarState(
        startDate = today.minus(14, DateTimeUnit.DAY),
        endDate = today.plus(120, DateTimeUnit.DAY),
        firstVisibleWeekDate = selectedDate,
        firstDayOfWeek = firstDayOfWeekFromLocale(),
    )
    WeekCalendar(state = state, dayContent = { day: WeekDay -> AgendaDayCell(day.date, selectedDate, today, onSelect) })
}

@Composable
private fun AgendaDayCell(date: LocalDate, selectedDate: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val selected = date == selectedDate
    Column(
        Modifier.padding(2.dp).clickable { onSelect(date) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ScheduleFormat.weekdayShort(date.dayOfWeek).take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.day}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    date == today -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun AgendaRow(run: ScheduleRun, subtitle: String?, now: Instant, zone: TimeZone, onClick: () -> Unit) {
    val ldt = run.instant.toLocalDateTime(zone)
    val time = "${ScheduleFormat.pad2(ldt.hour)}:${ScheduleFormat.pad2(ldt.minute)}"
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp).padding(top = 12.dp))
        // Status dot on a connecting vertical line.
        Box(Modifier.width(24.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(10.dp).clip(CircleShape).background(statusColor(run.status)))
        }
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(run.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val (label, color) = agendaStatusLabel(run, now)
        Text(if (run.status == RunStatus.Done) "Ran $time" else label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 12.dp))
    }
}

// --- Timeline swimlanes -----------------------------------------------------

@Composable
private fun TimelineView(
    defs: List<ScheduleDef>,
    weekStart: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: TimeZone,
    onLaneClick: (String) -> Unit,
) {
    val timeline = remember(defs, weekStart, now) {
        ScheduleProjection.timeline(defs, now, zone, startDate = weekStart, days = 7)
    }
    val days = remember(weekStart) { (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) } }
    val nowFrac = remember(now, zone) { now.toLocalDateTime(zone).let { (it.hour * 60 + it.minute) / 1440f } }
    val laneLabelWidth = 200.dp
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp)) {
        // Day header (today gets a "now" pill).
        Row(Modifier.fillMaxWidth().padding(start = laneLabelWidth, bottom = 8.dp)) {
            days.forEach { date ->
                val isToday = date == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${ScheduleFormat.weekdayShort(date.dayOfWeek)} ${date.day}", style = MaterialTheme.typography.labelSmall, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isToday) {
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 1.dp)) {
                            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items = timeline.lanes, key = { it.scheduleId }) { lane ->
                val highFreq = lane.ticks.size > 7 * WEEK_GRID_MAX_PER_DAY
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clickable { onLaneClick(lane.scheduleId) }
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(laneLabelWidth).padding(end = 12.dp)) {
                        Text(lane.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lane.cadence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    days.forEach { date ->
                        TimelineDayCell(
                            date = date,
                            today = today,
                            highFreq = highFreq,
                            nowFrac = nowFrac,
                            ticks = lane.ticks.filter { it.instant.toLocalDateTime(zone).date == date },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        TimelineLegend()
    }
}

@Composable
private fun TimelineDayCell(
    date: LocalDate,
    today: LocalDate,
    highFreq: Boolean,
    nowFrac: Float,
    ticks: List<com.letta.mobile.data.schedules.TimelineTick>,
    modifier: Modifier = Modifier,
) {
    val success = MaterialTheme.customColors.successColor
    val upcoming = MaterialTheme.colorScheme.outline
    val isToday = date == today
    Box(
        modifier.fillMaxHeight().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        if (highFreq && ticks.isNotEmpty()) {
            // Hourly+ schedules render as a continuous bar (filled = past). Only
            // paint days that actually have ticks, so a day-restricted
            // high-frequency cron doesn't show density on its off days.
            Row(Modifier.fillMaxWidth(0.86f).height(14.dp).clip(RoundedCornerShape(3.dp))) {
                when {
                    date < today -> Box(Modifier.fillMaxSize().background(success))
                    date > today -> Box(Modifier.fillMaxSize().border(1.dp, upcoming, RoundedCornerShape(3.dp)))
                    else -> {
                        val frac = nowFrac.coerceIn(0.02f, 0.98f)
                        Box(Modifier.weight(frac).fillMaxHeight().background(success))
                        Box(Modifier.weight(1f - frac).fillMaxHeight().border(1.dp, upcoming))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ticks.take(6).forEach { tick ->
                    val past = tick.status == RunStatus.Done || tick.status == RunStatus.Failed
                    Box(
                        Modifier.size(width = 6.dp, height = 18.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (past) statusColor(tick.status) else Color.Transparent)
                            .border(1.dp, statusColor(tick.status).copy(alpha = if (past) 1f else 0.6f), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        // Vertical now-line through today's cell.
        if (isToday) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(nowFrac.coerceIn(0.01f, 0.99f)))
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.weight(1f - nowFrac.coerceIn(0.01f, 0.99f)))
            }
        }
    }
}

@Composable
private fun TimelineLegend() {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(MaterialTheme.customColors.successColor, "success", filled = true)
        LegendItem(MaterialTheme.colorScheme.error, "failed", filled = true)
        LegendItem(MaterialTheme.colorScheme.outline, "upcoming", filled = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 2.dp, height = 14.dp).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(5.dp))
            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (filled) color else Color.Transparent).border(1.dp, color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- History ----------------------------------------------------------------

@Composable
private fun HistoryView(summary: com.letta.mobile.data.schedules.HistorySummary) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { StatsCard(summary) }
        item { Text("RELIABILITY · LAST 12 RUNS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor) }
        items(items = summary.schedules, key = { it.scheduleId }) { rel ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rel.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    val ok = rel.failed == 0
                    Text("${rel.succeeded}/${rel.total}", style = MaterialTheme.typography.labelMedium, color = if (ok) MaterialTheme.customColors.successColor else MaterialTheme.customColors.runningColor)
                }
                Spacer(Modifier.height(6.dp))
                ReliabilityStrip(rel.squares)
            }
        }
        if (summary.totalRuns == 0) {
            item {
                Text("No run history recorded yet — schedules will populate this as they fire.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            }
        }
    }
}

@Composable
private fun StatsCard(summary: com.letta.mobile.data.schedules.HistorySummary) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 18.dp),
    ) {
        StatCell("${summary.totalRuns}", "runs · 30d", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        StatCell(summary.overallSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "—", "success", MaterialTheme.customColors.successColor, Modifier.weight(1f))
        StatCell("—", "avg run", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(value: String, label: String, valueColor: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 18.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
private fun ReliabilityStrip(squares: List<Boolean?>, count: Int = 12) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val padded = (squares.takeLast(count) + List(count) { null }).take(count)
        padded.forEach { ok ->
            val color = when (ok) {
                true -> MaterialTheme.customColors.successColor
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

// --- Right rail -------------------------------------------------------------

@Composable
private fun ScheduleRail(
    rail: RailState,
    defs: List<ScheduleDef>,
    defsById: Map<String, ScheduleDef>,
    history: com.letta.mobile.data.schedules.HistorySummary,
    now: Instant,
    zone: TimeZone,
    nativeDefIds: Set<String>,
    onSelectSchedule: (String) -> Unit,
    onBackToOverview: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Box(
        Modifier.width(RAIL_WIDTH).fillMaxHeight()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        when (rail) {
            RailState.Overview -> OverviewRail(defs, history, now, zone, onSelectSchedule)
            is RailState.Detail -> defsById[rail.scheduleId]?.let { def ->
                DetailRail(
                    def = def,
                    reliability = history.schedules.firstOrNull { it.scheduleId == def.id },
                    now = now,
                    zone = zone,
                    // Native schedule-admin schedules aren't deletable via the
                    // cron API, so only offer Delete for cron-backed defs.
                    canDelete = def.id !in nativeDefIds,
                    onBack = onBackToOverview,
                    onDelete = onDelete,
                )
            } ?: OverviewRail(defs, history, now, zone, onSelectSchedule)
            is RailState.Run -> RunDetailRail(rail.run, zone, onBackToOverview)
        }
    }
}

@Composable
private fun OverviewRail(
    defs: List<ScheduleDef>,
    history: com.letta.mobile.data.schedules.HistorySummary,
    now: Instant,
    zone: TimeZone,
    onSelectSchedule: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("OVERVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            StatCell("${history.totalRuns}", "runs · 30d", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatCell(history.overallSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "—", "success", MaterialTheme.customColors.successColor, Modifier.weight(1f))
            StatCell("—", "avg", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Text("ACTIVE SCHEDULES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(10.dp))
        val globalNext = defs.mapNotNull { ScheduleProjection.nextRun(it, now) }.minOrNull()
        defs.forEach { def ->
            ActiveScheduleRow(def, now, zone, isNext = ScheduleProjection.nextRun(def, now) == globalNext && globalNext != null) { onSelectSchedule(def.id) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActiveScheduleRow(def: ScheduleDef, now: Instant, zone: TimeZone, isNext: Boolean, onClick: () -> Unit) {
    val next = ScheduleProjection.nextRun(def, now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (isNext) MaterialTheme.customColors.runningColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(def.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(def.cron?.let { CronSchedule.parse(it)?.let(CronSchedule::describe) ?: it } ?: "One-time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (next != null) ScheduleFormat.relative(now, next) else "—",
            style = MaterialTheme.typography.labelMedium,
            color = if (isNext) MaterialTheme.customColors.runningColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailRail(
    def: ScheduleDef,
    reliability: com.letta.mobile.data.schedules.ScheduleReliability?,
    now: Instant,
    zone: TimeZone,
    canDelete: Boolean,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onBack)) {
            Icon(Icons.Outlined.ArrowBack, "Overview", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Overview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text(def.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.customColors.successColor, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(if (def.active) "Active" else "Paused", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.successColor)
        }
        Spacer(Modifier.height(14.dp))
        val next = ScheduleProjection.nextRun(def, now)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(if (next != null) "Runs ${ScheduleFormat.relative(now, next)}" else "No upcoming run", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (next != null) {
                    Text("${ScheduleFormat.dateLabel(next, zone)} ${ScheduleFormat.timeOfDay(next, zone)} · ${def.zone.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DefRow("Cadence", def.cron?.let { CronSchedule.parse(it)?.let(CronSchedule::describe) ?: it } ?: "One-time")
        DefRow("Agent", def.name)
        DefRow("If missed", "Skip — don't catch up")
        Spacer(Modifier.height(14.dp))
        if (reliability != null && reliability.total > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RELIABILITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, modifier = Modifier.weight(1f))
                Text("${reliability.succeeded}/${reliability.total}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            ReliabilityStrip(reliability.squares)
            Spacer(Modifier.height(14.dp))
        }
        DesktopDefaultButton(onClick = { /* run now: backend-gated */ }, enabled = false, modifier = Modifier.fillMaxWidth()) {
            DesktopButtonContent("Run now")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopOutlinedButton(onClick = {}, enabled = false) { DesktopButtonContent("Pause") }
            // Delete only for cron-backed schedules — the wired callback hits
            // the cron API, which can't delete native schedule-admin schedules.
            if (canDelete) {
                DesktopOutlinedButton(onClick = { onDelete(def.id) }) { DesktopButtonContent("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        val controlNote = if (canDelete) {
            "Run now / Pause need a backend control endpoint (not available yet)."
        } else {
            "Run now / Pause / Delete need the native schedule control endpoints (not available yet)."
        }
        Text(controlNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
private fun RunDetailRail(run: ScheduleRun, zone: TimeZone, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onBack)) {
            Icon(Icons.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Overview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(run.status)))
            Spacer(Modifier.width(8.dp))
            Text(run.scheduleName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        DefRow("When", "${ScheduleFormat.dateLabel(run.instant, zone)} · ${ScheduleFormat.timeOfDay(run.instant, zone)}")
        DefRow("Status", run.status.name)
        run.durationMillis?.let { DefRow("Duration", ScheduleFormat.duration(it)) }
        Spacer(Modifier.height(12.dp))
        Text("OUTPUT LOG", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp)) {
            Text(
                run.error ?: "No captured output for this run yet.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (run.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Create modal -----------------------------------------------------------

@Composable
private fun CreateScheduleModal(
    now: Instant,
    zone: TimeZone,
    canCreate: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, prompt: String, cron: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(CronBuilderState()) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(720.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}.padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New schedule", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
            }
            Spacer(Modifier.height(16.dp))
            Text("NAME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            Spacer(Modifier.height(6.dp))
            DesktopTextField(value = name, onValueChange = { name = it }, placeholder = "Morning briefing", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text("PROMPT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            Spacer(Modifier.height(6.dp))
            DesktopTextArea(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "What should the agent do when this fires?",
                modifier = Modifier.fillMaxWidth().height(140.dp),
                decorationBoxModifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("WHEN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            Spacer(Modifier.height(6.dp))
            CadencePicker(draft) { draft = it }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) {
                Column {
                    Text(CronBuilder.preview(draft), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    CronBuilder.previewRuns(draft, now, zone, 3).forEach {
                        Text("• ${ScheduleFormat.dateLabel(it, zone)} · ${ScheduleFormat.timeOfDay(it, zone)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            val expression = CronBuilder.toExpression(draft)
            val valid = expression != null && name.isNotBlank() && prompt.isNotBlank() && canCreate
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = valid) { expression?.let { onCreate(name.trim(), prompt.trim(), it) } }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("Create schedule", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (valid) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!canCreate) {
                Spacer(Modifier.height(6.dp))
                Text("This backend doesn't allow creating schedules.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CadencePicker(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cadenceChip("Every N min", draft.cadence == CronCadence.EveryNMinutes) { onChange(draft.copy(cadence = CronCadence.EveryNMinutes)) }
            cadenceChip("Hourly", draft.cadence == CronCadence.Hourly) { onChange(draft.copy(cadence = CronCadence.Hourly)) }
            cadenceChip("Daily", draft.cadence == CronCadence.Daily) { onChange(draft.copy(cadence = CronCadence.Daily)) }
            cadenceChip("Weekly", draft.cadence == CronCadence.Weekly) { onChange(draft.copy(cadence = CronCadence.Weekly)) }
            cadenceChip("Custom", draft.cadence == CronCadence.Custom) { onChange(draft.copy(cadence = CronCadence.Custom)) }
        }
        Spacer(Modifier.height(10.dp))
        when (draft.cadence) {
            CronCadence.EveryNMinutes -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 10, 15, 30).forEach { n -> cadenceChip("$n", draft.intervalMinutes == n) { onChange(draft.copy(intervalMinutes = n)) } }
            }
            CronCadence.Hourly -> TimeRow("Minute", draft.minute, 0, 59) { onChange(draft.copy(minute = it)) }
            CronCadence.Daily -> TimeOfDayRow(draft) { onChange(it) }
            CronCadence.Weekly -> Column {
                TimeOfDayRow(draft) { onChange(it) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { iso ->
                        val on = iso in draft.daysOfWeek
                        cadenceChip(ScheduleFormat.weekdayShort(iso).take(1), on) {
                            onChange(draft.copy(daysOfWeek = if (on) draft.daysOfWeek - iso else draft.daysOfWeek + iso))
                        }
                    }
                }
            }
            CronCadence.Custom -> DesktopTextField(value = draft.customExpression, onValueChange = { onChange(draft.copy(customExpression = it)) }, placeholder = "*/15 * * * *", modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TimeOfDayRow(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeRow("Hour", draft.hour, 0, 23) { onChange(draft.copy(hour = it)) }
        TimeRow("Minute", draft.minute, 0, 59) { onChange(draft.copy(minute = it)) }
        Text(ScheduleFormat.clockLabel(draft.hour, draft.minute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
private fun TimeRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconBtn(Icons.Outlined.ChevronLeft, "−", { onChange((value - 1).coerceAtLeast(min)) })
        Text(ScheduleFormat.pad2(value), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        IconBtn(Icons.Outlined.ChevronRight, "+", { onChange((value + 1).coerceAtMost(max)) })
    }
}

// --- Shared bits ------------------------------------------------------------

@Composable
private fun DefRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
    }
}

@Composable
private fun cadenceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ScheduleEmptyState(canCreate: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.customColors.onSurfaceMutedColor, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text("No schedules yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (canCreate) "Use “New schedule” to create one." else "Schedules created elsewhere will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
    }
}

@Composable
private fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.Done -> MaterialTheme.customColors.successColor
    RunStatus.Failed -> MaterialTheme.colorScheme.error
    RunStatus.Running, RunStatus.Next -> MaterialTheme.customColors.runningColor
    RunStatus.Upcoming -> MaterialTheme.colorScheme.outline
}

@Composable
private fun agendaStatusLabel(run: ScheduleRun, now: Instant): Pair<String, Color> = when (run.status) {
    RunStatus.Done -> "Ran" to MaterialTheme.customColors.successColor
    RunStatus.Failed -> "Failed" to MaterialTheme.colorScheme.error
    RunStatus.Running -> "running" to MaterialTheme.customColors.runningColor
    RunStatus.Next -> ScheduleFormat.relative(now, run.instant) to MaterialTheme.customColors.runningColor
    RunStatus.Upcoming -> "scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
}

private fun monthDayLabel(date: LocalDate): String =
    "${ScheduleFormat.monthShort(date.month.ordinal + 1)} ${date.day}"

private fun dayHeading(date: LocalDate, today: LocalDate): String {
    val delta = (date.toEpochDays() - today.toEpochDays()).toInt()
    return when (delta) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> "${fullWeekday(date.dayOfWeek.isoDayNumber)}, ${monthDayLabel(date)}"
    }
}

private fun fullWeekday(iso: Int): String =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[(iso - 1).coerceIn(0, 6)]

private fun fullMonth(monthNumber: Int): String =
    listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )[(monthNumber - 1).coerceIn(0, 11)]
