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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.CronBuilder
import com.letta.mobile.data.schedules.CronBuilderState
import com.letta.mobile.data.schedules.CronCadence
import com.letta.mobile.data.schedules.CronSchedule
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopIconButton
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** The segmented views over one schedule dataset (Phase 7). */
private enum class ScheduleView(val label: String) {
    Agenda("Agenda"),
    Week("Week"),
    Timeline("Timeline"),
    History("History"),
    Rules("Rules"),
}

/** What the master-detail right rail is showing. */
private sealed interface RailState {
    data object Hidden : RailState
    data class Detail(val scheduleId: String) : RailState
    data class Run(val run: ScheduleRun) : RailState
    data class Builder(val editingId: String?, val draft: CronBuilderState, val name: String, val prompt: String) :
        RailState
}

/**
 * The Phase-7 Schedules surface: a view switcher (Agenda / Week / Timeline /
 * History / Rules) over one cron dataset, with a master-detail right rail
 * (schedule detail, run detail, and a cron builder). All view logic comes from
 * the shared [ScheduleProjection]; this file is binding-only so the same logic
 * ports to mobile. Supersedes the flat [DesktopScheduleLibrarySurface], which
 * is now embedded as the "Rules" tab.
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
    // Captured once per entry — a stable "now" for the whole projection pass.
    val now = remember { Clock.System.now() }
    val today = remember(now, zone) { now.toLocalDateTime(zone).date }

    var filterAgentId by remember(focusedAgentId) { mutableStateOf(focusedAgentId) }
    val filteredCrons = remember(crons, filterAgentId) {
        if (filterAgentId == null) crons else crons.filter { it.agentId == null || it.agentId == filterAgentId }
    }
    val defs = remember(filteredCrons, zone) { ScheduleProjection.toScheduleDefs(filteredCrons, zone) }
    val defsById = remember(defs) { defs.associateBy { it.id } }

    var view by remember { mutableStateOf(ScheduleView.Agenda) }
    var rail by remember { mutableStateOf<RailState>(RailState.Hidden) }
    var selectedDate by remember(today) { mutableStateOf(today) }
    var weekStart by remember(today) { mutableStateOf(ScheduleProjection.mondayOf(today)) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ScheduleSurfaceHeader(
            count = filteredCrons.size,
            isLoading = state.isLoading,
            canCreate = canCreate,
            onRefresh = onRefresh,
            onNew = {
                rail = RailState.Builder(
                    editingId = null,
                    draft = CronBuilderState(),
                    name = "",
                    prompt = "",
                )
            },
        )
        ScheduleTabs(selected = view, onSelect = { view = it; rail = RailState.Hidden })
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (view) {
                    ScheduleView.Agenda -> AgendaView(defs, selectedDate, today, now, zone, onSelectDate = { selectedDate = it }) { run ->
                        rail = RailState.Run(run)
                    }
                    ScheduleView.Week -> WeekView(defs, weekStart, now, zone, onShiftWeek = { weekStart = weekStart.plus(it * 7, DateTimeUnit.DAY) }) { run ->
                        rail = RailState.Run(run)
                    }
                    ScheduleView.Timeline -> TimelineView(defs, now, zone) { scheduleId ->
                        rail = RailState.Detail(scheduleId)
                    }
                    ScheduleView.History -> HistoryView(defs, now, zone) { scheduleId ->
                        rail = RailState.Detail(scheduleId)
                    }
                    ScheduleView.Rules -> DesktopScheduleLibrarySurface(
                        state = state,
                        onRefresh = onRefresh,
                        onAgentSelected = onAgentSelected,
                        crons = crons,
                        focusedAgentId = focusedAgentId,
                        onDeleteCron = onDeleteCron,
                        canCreate = canCreate,
                        onCreateCron = onCreateCron,
                    )
                }
                // Empty-state hint for the projection views (Rules has its own).
                if (view != ScheduleView.Rules && defs.isEmpty()) {
                    ScheduleEmptyState(canCreate)
                }
            }
            val current = rail
            if (current != RailState.Hidden) {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    when (current) {
                        is RailState.Detail -> defsById[current.scheduleId]?.let { def ->
                            ScheduleDetailRail(
                                def = def,
                                now = now,
                                zone = zone,
                                onBack = { rail = RailState.Hidden },
                                onEdit = {
                                    val draft = def.cron?.let { CronBuilder.fromExpression(it) } ?: CronBuilderState()
                                    rail = RailState.Builder(def.id, draft, def.name, "")
                                },
                                onDelete = {
                                    onDeleteCron(def.id)
                                    rail = RailState.Hidden
                                },
                            )
                        } ?: run { rail = RailState.Hidden }
                        is RailState.Run -> RunDetailRail(
                            run = current.run,
                            zone = zone,
                            onBack = { rail = RailState.Hidden },
                            onGoToSchedule = { rail = RailState.Detail(current.run.scheduleId) },
                        )
                        is RailState.Builder -> CronBuilderRail(
                            initial = current,
                            now = now,
                            zone = zone,
                            canCreate = canCreate,
                            onCancel = { rail = RailState.Hidden },
                            onSubmit = { expression, name, prompt ->
                                // Edit = delete-then-recreate (no update endpoint).
                                current.editingId?.let(onDeleteCron)
                                onCreateCron(filterAgentId, name, prompt, expression, true, zone.id)
                                rail = RailState.Hidden
                            },
                        )
                        RailState.Hidden -> Unit
                    }
                }
            }
        }
    }
}

// --- Header + tabs ----------------------------------------------------------

@Composable
private fun ScheduleSurfaceHeader(
    count: Int,
    isLoading: Boolean,
    canCreate: Boolean,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text("Schedules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.weight(1f))
        DesktopOutlinedButton(onClick = onRefresh, enabled = !isLoading) {
            DesktopButtonContent(if (isLoading) "Refreshing" else "Refresh", Icons.Outlined.Refresh)
        }
        if (canCreate) {
            Spacer(Modifier.width(10.dp))
            DesktopDefaultButton(onClick = onNew) {
                DesktopButtonContent("New schedule", Icons.Outlined.Add)
            }
        }
    }
}

@Composable
private fun ScheduleTabs(selected: ScheduleView, onSelect: (ScheduleView) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 28.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ScheduleView.entries.forEach { view ->
            val active = view == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .clickable { onSelect(view) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(
                    view.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
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
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        AgendaDateStrip(selectedDate, today, onSelectDate)
        Spacer(Modifier.height(16.dp))
        if (agenda.runs.isEmpty()) {
            Text("No runs scheduled for this day.", color = MaterialTheme.customColors.onSurfaceMutedColor, style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = agenda.runs, key = { it.scheduleId + it.instant.toString() }) { run ->
                    AgendaRow(run, zone, onClick = { onRunClick(run) })
                }
            }
        }
    }
}

@Composable
private fun AgendaDateStrip(selectedDate: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val days = remember(today) { (-7..21).map { today.plus(it, DateTimeUnit.DAY) } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = days, key = { it.toString() }) { date ->
            val selected = date == selectedDate
            val isToday = date == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 1.dp,
                        color = if (isToday && !selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(date) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                val onColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Text(ScheduleFormat.weekdayShort(date.dayOfWeek), style = MaterialTheme.typography.labelSmall, color = onColor)
                Text("${date.day}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun AgendaRow(run: ScheduleRun, zone: TimeZone, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(run.status)
        Spacer(Modifier.width(12.dp))
        Text(ScheduleFormat.timeOfDay(run.instant, zone), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(84.dp))
        Spacer(Modifier.width(8.dp))
        Text(run.scheduleName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        StatusPill(run.status)
    }
}

// --- Week time-grid ---------------------------------------------------------

@Composable
private fun WeekView(
    defs: List<ScheduleDef>,
    weekStart: LocalDate,
    now: Instant,
    zone: TimeZone,
    onShiftWeek: (Int) -> Unit,
    onRunClick: (ScheduleRun) -> Unit,
) {
    val grid = remember(defs, weekStart, now) { ScheduleProjection.week(defs, weekStart, now, zone) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            DesktopIconButton(Icons.Outlined.ArrowBack, "Previous week", onClick = { onShiftWeek(-1) })
            Text(
                "${monthDayLabel(grid.days.first())} – ${monthDayLabel(grid.days.last())}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            DesktopIconButton(Icons.Outlined.PlayArrow, "Next week", onClick = { onShiftWeek(1) })
        }
        // Day header row.
        Row(Modifier.fillMaxWidth().padding(start = 44.dp)) {
            grid.days.forEach { date ->
                Text(
                    "${ScheduleFormat.weekdayShort(date.dayOfWeek)} ${date.day}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            (0..23).forEach { hour ->
                Row(Modifier.fillMaxWidth().height(38.dp)) {
                    Text(
                        ScheduleFormat.pad2(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.customColors.onSurfaceMutedColor,
                        modifier = Modifier.width(44.dp),
                    )
                    (0..6).forEach { day ->
                        val cellRuns = grid.runs.filter { it.dayIndex == day && it.minuteOfDay / 60 == hour }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                cellRuns.take(4).forEach { wr ->
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(statusColor(wr.run.status))
                                            .clickable { onRunClick(wr.run) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Timeline swimlanes -----------------------------------------------------

@Composable
private fun TimelineView(
    defs: List<ScheduleDef>,
    now: Instant,
    zone: TimeZone,
    onLaneClick: (String) -> Unit,
) {
    val timeline = remember(defs, now) { ScheduleProjection.timeline(defs, now, zone, days = 7) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items = timeline.lanes, key = { it.scheduleId }) { lane ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onLaneClick(lane.scheduleId) }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lane.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(lane.cadence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (lane.ticks.isEmpty()) {
                        Text("No runs in the next 7 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
                    } else {
                        lane.ticks.take(40).forEach { tick ->
                            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(tick.status)))
                        }
                    }
                }
            }
        }
    }
}

// --- History ----------------------------------------------------------------

@Composable
private fun HistoryView(
    defs: List<ScheduleDef>,
    now: Instant,
    zone: TimeZone,
    onScheduleClick: (String) -> Unit,
) {
    val summary = remember(defs, now) { ScheduleProjection.history(defs, now, zone) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val rate = summary.overallSuccessRate
                Text(
                    if (rate == null) "—" else "${(rate * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.customColors.successColor,
                )
                Spacer(Modifier.width(10.dp))
                Text("success rate · ${summary.totalRuns} runs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
            }
        }
        items(items = summary.schedules, key = { it.scheduleId }) { rel ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onScheduleClick(rel.scheduleId) }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rel.scheduleName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${rel.succeeded}/${rel.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                ReliabilityStrip(rel.squares)
            }
        }
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
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

// --- Right rail: schedule detail --------------------------------------------

@Composable
private fun ScheduleDetailRail(
    def: ScheduleDef,
    now: Instant,
    zone: TimeZone,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        RailBackHeader(onBack)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            ActivePill(def.active)
        }
        Spacer(Modifier.height(12.dp))
        val next = ScheduleProjection.nextRun(def, now)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
            Text("NEXT RUN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
            Spacer(Modifier.height(4.dp))
            Text(
                if (next == null) "No upcoming run" else ScheduleFormat.relative(now, next),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.customColors.runningColor,
            )
            if (next != null) {
                Text(ScheduleFormat.dateLabel(next, zone) + " · " + ScheduleFormat.timeOfDay(next, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        DefRow("Cadence", def.cron?.let { CronSchedule.parse(it)?.let(CronSchedule::describe) ?: it } ?: "One-time")
        DefRow("Fired", "${def.fireCount} times")
        def.lastFiredAt?.let { DefRow("Last run", ScheduleFormat.relative(now, it)) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopDefaultButton(onClick = onEdit) { DesktopButtonContent("Edit", Icons.Outlined.Edit) }
            DesktopOutlinedButton(onClick = onDelete) { DesktopButtonContent("Delete", Icons.Outlined.Delete) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Run now / Pause need a backend control endpoint (not available yet).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

// --- Right rail: run detail -------------------------------------------------

@Composable
private fun RunDetailRail(
    run: ScheduleRun,
    zone: TimeZone,
    onBack: () -> Unit,
    onGoToSchedule: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        RailBackHeader(onBack)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(run.status)
            Spacer(Modifier.width(8.dp))
            Text(run.scheduleName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        DefRow("When", ScheduleFormat.dateLabel(run.instant, zone) + " · " + ScheduleFormat.timeOfDay(run.instant, zone))
        DefRow("Status", run.status.name)
        run.durationMillis?.let { DefRow("Duration", ScheduleFormat.duration(it)) }
        run.exitCode?.let { DefRow("Exit code", it.toString()) }
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
        Spacer(Modifier.height(14.dp))
        DesktopOutlinedButton(onClick = onGoToSchedule) { DesktopButtonContent("Go to schedule", Icons.Outlined.Schedule) }
    }
}

// --- Right rail: cron builder ----------------------------------------------

@Composable
private fun CronBuilderRail(
    initial: RailState.Builder,
    now: Instant,
    zone: TimeZone,
    canCreate: Boolean,
    onCancel: () -> Unit,
    onSubmit: (expression: String, name: String, prompt: String) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial.draft) }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var prompt by remember(initial) { mutableStateOf(initial.prompt) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        RailBackHeader(onCancel)
        Text(if (initial.editingId == null) "New schedule" else "Edit schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(14.dp))

        SectionLabel("CADENCE")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                CronCadence.EveryNMinutes to "Every N min",
                CronCadence.Hourly to "Hourly",
                CronCadence.Daily to "Daily",
            ).forEach { (cadence, label) ->
                CadenceChip(label, draft.cadence == cadence) { draft = draft.copy(cadence = cadence) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            CadenceChip("Weekly", draft.cadence == CronCadence.Weekly) { draft = draft.copy(cadence = CronCadence.Weekly) }
            CadenceChip("Custom", draft.cadence == CronCadence.Custom) { draft = draft.copy(cadence = CronCadence.Custom) }
        }
        Spacer(Modifier.height(14.dp))

        when (draft.cadence) {
            CronCadence.EveryNMinutes -> {
                SectionLabel("INTERVAL (MINUTES)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 30).forEach { n ->
                        CadenceChip("$n", draft.intervalMinutes == n) { draft = draft.copy(intervalMinutes = n) }
                    }
                }
            }
            CronCadence.Hourly -> {
                SectionLabel("MINUTE OF HOUR")
                NumberStepper(draft.minute, 0, 59) { draft = draft.copy(minute = it) }
            }
            CronCadence.Daily -> TimeOfDayPicker(draft.hour, draft.minute, { draft = draft.copy(hour = it) }, { draft = draft.copy(minute = it) })
            CronCadence.Weekly -> {
                TimeOfDayPicker(draft.hour, draft.minute, { draft = draft.copy(hour = it) }, { draft = draft.copy(minute = it) })
                Spacer(Modifier.height(10.dp))
                SectionLabel("DAYS")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { iso ->
                        val on = iso in draft.daysOfWeek
                        CadenceChip(ScheduleFormat.weekdayShort(iso).take(1), on) {
                            draft = draft.copy(daysOfWeek = if (on) draft.daysOfWeek - iso else draft.daysOfWeek + iso)
                        }
                    }
                }
            }
            CronCadence.Custom -> {
                SectionLabel("CRON EXPRESSION")
                DesktopTextField(value = draft.customExpression, onValueChange = { draft = draft.copy(customExpression = it) }, placeholder = "*/15 * * * *", modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(16.dp))
        // Live preview.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
            Text(CronBuilder.preview(draft), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            val previews = remember(draft, now) { CronBuilder.previewRuns(draft, now, zone, 3) }
            previews.forEach {
                Text("• " + ScheduleFormat.dateLabel(it, zone) + " · " + ScheduleFormat.timeOfDay(it, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("NAME")
        DesktopTextField(value = name, onValueChange = { name = it }, placeholder = "Morning briefing", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SectionLabel("PROMPT")
        DesktopTextArea(value = prompt, onValueChange = { prompt = it }, placeholder = "What should the agent do?", modifier = Modifier.fillMaxWidth().height(80.dp))

        Spacer(Modifier.height(16.dp))
        val expression = CronBuilder.toExpression(draft)
        val valid = expression != null && name.isNotBlank() && prompt.isNotBlank() && canCreate
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopDefaultButton(onClick = { expression?.let { onSubmit(it, name.trim(), prompt.trim()) } }, enabled = valid) {
                DesktopButtonContent(if (initial.editingId == null) "Create" else "Save", Icons.Outlined.CheckCircle)
            }
            DesktopOutlinedButton(onClick = onCancel) { DesktopButtonContent("Cancel") }
        }
        if (!canCreate) {
            Spacer(Modifier.height(6.dp))
            Text("This backend doesn't allow creating schedules.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// --- Small shared bits ------------------------------------------------------

@Composable
private fun RailBackHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onBack)) {
        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Back", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DefRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CadenceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NumberStepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopIconButton(Icons.Outlined.ArrowBack, "Decrease", onClick = { onChange((value - 1).coerceAtLeast(min)) })
        Text(ScheduleFormat.pad2(value), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        DesktopIconButton(Icons.Outlined.PlayArrow, "Increase", onClick = { onChange((value + 1).coerceAtMost(max)) })
    }
}

@Composable
private fun TimeOfDayPicker(hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) {
    Column {
        SectionLabel("TIME")
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberStepper(hour, 0, 23, onHour)
            Text(" : ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberStepper(minute, 0, 59, onMinute)
            Spacer(Modifier.width(10.dp))
            Text(ScheduleFormat.clockLabel(hour, minute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        }
    }
}

@Composable
private fun StatusDot(status: RunStatus) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(status)))
}

@Composable
private fun StatusPill(status: RunStatus) {
    val color = statusColor(status)
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(status.name, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ActivePill(active: Boolean) {
    val color = if (active) MaterialTheme.customColors.successColor else MaterialTheme.customColors.runningColor
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(if (active) "Active" else "Paused", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ScheduleEmptyState(canCreate: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.customColors.onSurfaceMutedColor, modifier = Modifier.size(40.dp))
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

/** A short `Jun 24`-style label for a date (no Instant conversion needed). */
private fun monthDayLabel(date: LocalDate): String =
    "${ScheduleFormat.monthShort(date.month.ordinal + 1)} ${date.day}"
