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

// --- Right rail -------------------------------------------------------------

internal data class ScheduleRailParams(
    val rail: RailState,
    val defs: List<ScheduleDef>,
    val defsById: Map<String, ScheduleDef>,
    val history: com.letta.mobile.data.schedules.HistorySummary,
    val now: Instant,
    val zone: TimeZone,
    val onSelectSchedule: (String) -> Unit,
    val onBackToOverview: () -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
internal fun ScheduleRail(params: ScheduleRailParams) {
    Box(
        Modifier.width(RAIL_WIDTH).fillMaxHeight()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        ScheduleRailContent(params)
    }
}

@Composable
private fun ScheduleRailContent(params: ScheduleRailParams) {
    when (val rail = params.rail) {
        RailState.Overview -> OverviewRail(
            OverviewRailParams(
                defs = params.defs,
                history = params.history,
                now = params.now,
                zone = params.zone,
                onSelectSchedule = params.onSelectSchedule,
            ),
        )
        is RailState.Detail -> {
            val def = params.defsById[rail.scheduleId]
            if (def != null) {
                DetailRail(
                    DetailRailParams(
                        def = def,
                        reliability = params.history.schedules.firstOrNull { it.scheduleId == def.id },
                        now = params.now,
                        zone = params.zone,
                        // Delete is routed by the host: cron ids → CronApi, native
                        // schedule ids → scheduleRepository.deleteSchedule.
                        canDelete = true,
                        onBack = params.onBackToOverview,
                        onDelete = params.onDelete,
                    ),
                )
            } else {
                OverviewRail(
                    OverviewRailParams(
                        defs = params.defs,
                        history = params.history,
                        now = params.now,
                        zone = params.zone,
                        onSelectSchedule = params.onSelectSchedule,
                    ),
                )
            }
        }
        is RailState.Run -> RunDetailRail(rail.run, params.zone, params.onBackToOverview)
    }
}

internal data class OverviewRailParams(
    val defs: List<ScheduleDef>,
    val history: com.letta.mobile.data.schedules.HistorySummary,
    val now: Instant,
    val zone: TimeZone,
    val onSelectSchedule: (String) -> Unit,
)

@Composable
internal fun OverviewRail(params: OverviewRailParams) {
    val history = params.history
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
        val globalNext = params.defs.mapNotNull { ScheduleProjection.nextRun(it, params.now) }.minOrNull()
        params.defs.forEach { def ->
            val next = ScheduleProjection.nextRun(def, params.now)
            ActiveScheduleRow(
                ActiveScheduleRowParams(
                    def = def,
                    now = params.now,
                    zone = params.zone,
                    isNext = next == globalNext && globalNext != null,
                    onClick = { params.onSelectSchedule(def.id) },
                ),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

internal data class ActiveScheduleRowParams(
    val def: ScheduleDef,
    val now: Instant,
    val zone: TimeZone,
    val isNext: Boolean,
    val onClick: () -> Unit,
)

internal fun cadenceLabel(def: ScheduleDef): String =
    def.cron?.let { CronSchedule.parse(it)?.let(CronSchedule::describe) ?: it } ?: "One-time"

internal fun relativeOrDash(now: Instant, next: Instant?): String =
    if (next != null) ScheduleFormat.relative(now, next) else "—"

internal fun activeStatusLabel(active: Boolean): String = if (active) "Active" else "Paused"

internal fun nextRunHeadline(now: Instant, next: Instant?): String =
    if (next != null) "Runs ${ScheduleFormat.relative(now, next)}" else "No upcoming run"

internal fun detailControlNote(canDelete: Boolean): String =
    if (canDelete) {
        "Run now / Pause need a backend control endpoint (not available yet)."
    } else {
        "Run now / Pause / Delete need the native schedule control endpoints (not available yet)."
    }

@Composable
internal fun ActiveScheduleRow(params: ActiveScheduleRowParams) {
    val next = ScheduleProjection.nextRun(params.def, params.now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (params.isNext) MaterialTheme.customColors.runningColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = params.onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(params.def.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cadenceLabel(params.def), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            relativeOrDash(params.now, next),
            style = MaterialTheme.typography.labelMedium,
            color = if (params.isNext) MaterialTheme.customColors.runningColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal data class DetailRailParams(
    val def: ScheduleDef,
    val reliability: com.letta.mobile.data.schedules.ScheduleReliability?,
    val now: Instant,
    val zone: TimeZone,
    val canDelete: Boolean,
    val onBack: () -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
internal fun DetailRail(params: DetailRailParams) {
    val def = params.def
    val reliability = params.reliability
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = params.onBack)) {
            Icon(Icons.Outlined.ArrowBack, "Overview", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Overview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text(def.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.customColors.successColor, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(activeStatusLabel(def.active), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.successColor)
        }
        Spacer(Modifier.height(14.dp))
        val next = ScheduleProjection.nextRun(def, params.now)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(nextRunHeadline(params.now, next), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (next != null) {
                    Text("${ScheduleFormat.dateLabel(next, params.zone)} ${ScheduleFormat.timeOfDay(next, params.zone)} · ${def.zone.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DefRow("Cadence", cadenceLabel(def))
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
            if (params.canDelete) {
                DesktopOutlinedButton(onClick = { params.onDelete(def.id) }) { DesktopButtonContent("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(detailControlNote(params.canDelete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
internal fun RunDetailRail(run: ScheduleRun, zone: TimeZone, onBack: () -> Unit) {
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

