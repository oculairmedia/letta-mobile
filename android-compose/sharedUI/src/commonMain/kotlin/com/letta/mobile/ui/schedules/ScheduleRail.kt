package com.letta.mobile.ui.schedules

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CronSchedule
import com.letta.mobile.data.schedules.HistorySummary
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.ScheduleReliability
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What the schedules rail shows: the overview, one schedule's detail, or one run's detail. */
sealed interface ScheduleRailState {
    data object Overview : ScheduleRailState
    data class Detail(val scheduleId: String) : ScheduleRailState
    data class Run(val run: ScheduleRun) : ScheduleRailState
}

/** Test tags of the schedules rail. */
object ScheduleRailTags {
    const val ACTIVE_LIST = "schedule-rail-active"
}

val ScheduleRailWidth = 372.dp

/** What the rail draws from and asks its host to do. A null [onTogglePause] disables Pause / Resume. */
@Immutable
data class ScheduleRailParams(
    val rail: ScheduleRailState,
    val defs: List<ScheduleDef>,
    val defsById: Map<String, ScheduleDef>,
    val history: HistorySummary,
    val now: Instant,
    val zone: TimeZone,
    val onSelectSchedule: (String) -> Unit,
    val onBackToOverview: () -> Unit,
    val onDelete: (String) -> Unit,
    val onTogglePause: ((String, Boolean) -> Unit)? = null,
)

/**
 * letta-mobile-ckmub.3.10 (hard cut S7): the schedules page's right rail, shared. The overview's
 * active schedules are a [RosterList]; a schedule or a run opens its detail in place.
 */
@Composable
fun ScheduleRail(params: ScheduleRailParams) {
    Box(
        Modifier.width(ScheduleRailWidth).fillMaxHeight()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surface)
            .padding(LettaDimens.Space.xl),
    ) {
        when (val rail = params.rail) {
            ScheduleRailState.Overview -> ScheduleOverviewRail(params)
            is ScheduleRailState.Detail -> params.defsById[rail.scheduleId]?.let { ScheduleDetailRail(it, params) } ?: ScheduleOverviewRail(params)
            is ScheduleRailState.Run -> ScheduleRunDetailRail(rail.run, params.zone, params.onBackToOverview)
        }
    }
}

/** The 30-day stats, then every active schedule, the next one to fire picked out. */
@Composable
fun ScheduleOverviewRail(params: ScheduleRailParams) {
    val history = params.history
    Column(Modifier.fillMaxSize()) {
        RailLabel("OVERVIEW")
        Spacer(Modifier.height(LettaDimens.Space.md))
        Row(Modifier.fillMaxWidth()) {
            StatCell("${history.totalRuns}", "runs · 30d", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatCell(history.overallSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "—", "success", MaterialTheme.customColors.successColor, Modifier.weight(1f))
            StatCell("—", "avg", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        Spacer(Modifier.height(LettaDimens.Space.md))
        val nextId = remember(params.defs, params.now) { ScheduleRailText.nextToFire(params.defs, params.now) }
        val sections = remember(params.defs) { listOf(RosterSection("active", params.defs, title = "Active schedules")) }
        RosterList(
            RosterSpec(
                sections = sections,
                key = ScheduleDef::id,
                selection = RosterSelection(isSelected = { it.id == nextId }, onSelect = { params.onSelectSchedule(it.id) }),
                options = RosterOptions(testTag = RosterListTag(ScheduleRailTags.ACTIVE_LIST)),
            ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { def, row ->
            RosterListRow(
                spec = LettaListRowSpec(title = def.name, subtitle = ScheduleRailText.cadence(def), emphasized = true),
                row = row,
                slots = RosterRowSlots(end = { NextRunLabel(def, params.now, isNext = def.id == nextId) }),
            )
        }
    }
}

@Composable
private fun NextRunLabel(def: ScheduleDef, now: Instant, isNext: Boolean) {
    Text(
        ScheduleRailText.relativeOrDash(now, ScheduleProjection.nextRun(def, now)),
        style = MaterialTheme.typography.labelMedium,
        color = if (isNext) MaterialTheme.customColors.runningColor else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScheduleDetailRail(def: ScheduleDef, params: ScheduleRailParams) {
    val reliability = params.history.schedules.firstOrNull { it.scheduleId == def.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackToOverview("Overview", params.onBackToOverview)
        Spacer(Modifier.height(LettaDimens.Space.md))
        Text(def.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LettaDimens.Space.sm))
        StatusBadge(ScheduleRailText.status(def.active))
        Spacer(Modifier.height(LettaDimens.Space.lg))
        NextRunCard(def, params.now, params.zone)
        Spacer(Modifier.height(LettaDimens.Space.lg))
        ScheduleDefRow("Cadence", ScheduleRailText.cadence(def))
        ScheduleDefRow("Agent", def.name)
        ScheduleDefRow("If missed", "Skip — don't catch up")
        Spacer(Modifier.height(LettaDimens.Space.lg))
        reliability?.takeIf { it.total > 0 }?.let { ReliabilityBlock(it) }
        DetailControls(def, params)
    }
}

@Composable
private fun StatusBadge(label: String) {
    val shape = RoundedCornerShape(LettaDimens.Radius.sm)
    Box(Modifier.clip(shape).border(1.dp, MaterialTheme.customColors.successColor, shape).padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.successColor)
    }
}

@Composable
private fun NextRunCard(def: ScheduleDef, now: Instant, zone: TimeZone) {
    val next = ScheduleProjection.nextRun(def, now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(LettaDimens.Radius.md)).background(MaterialTheme.colorScheme.surfaceContainer).padding(LettaDimens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(LettaDimens.Control.icon))
        Spacer(Modifier.width(LettaDimens.Space.md))
        Column {
            Text(ScheduleRailText.nextRunHeadline(now, next), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (next != null) {
                Text(
                    "${ScheduleFormat.dateLabel(next, zone)} ${ScheduleFormat.timeOfDay(next, zone)} · ${def.zone.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReliabilityBlock(reliability: ScheduleReliability) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RailLabel("RELIABILITY", Modifier.weight(1f))
        Text("${reliability.succeeded}/${reliability.total}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(LettaDimens.Space.sm))
    ReliabilityStrip(reliability.squares)
    Spacer(Modifier.height(LettaDimens.Space.lg))
}

/** Run now (backend-gated), Pause / Resume, Delete, and the note on what the backend cannot do yet. */
@Composable
private fun DetailControls(def: ScheduleDef, params: ScheduleRailParams) {
    Button(onClick = { /* run now: backend-gated */ }, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Run now") }
    Spacer(Modifier.height(LettaDimens.Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        val isPaused = !def.active
        OutlinedButton(onClick = { params.onTogglePause?.invoke(def.id, isPaused) }, enabled = params.onTogglePause != null) {
            Text(if (isPaused) "Resume" else "Pause")
        }
        // Delete is routed by the host: cron ids to the cron API, native schedule ids to the schedule repository.
        OutlinedButton(onClick = { params.onDelete(def.id) }) { Text("Delete") }
    }
    Spacer(Modifier.height(LettaDimens.Space.sm))
    Text(ScheduleRailText.CONTROL_NOTE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
}

@Composable
private fun ScheduleRunDetailRail(run: ScheduleRun, zone: TimeZone, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackToOverview("Back", onBack)
        Spacer(Modifier.height(LettaDimens.Space.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(LettaDimens.Control.iconSm).clip(CircleShape).background(statusColor(run.status)))
            Spacer(Modifier.width(LettaDimens.Space.sm))
            Text(run.scheduleName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(LettaDimens.Space.md))
        ScheduleDefRow("When", "${ScheduleFormat.dateLabel(run.instant, zone)} · ${ScheduleFormat.timeOfDay(run.instant, zone)}")
        ScheduleDefRow("Status", run.status.name)
        run.durationMillis?.let { ScheduleDefRow("Duration", ScheduleFormat.duration(it)) }
        Spacer(Modifier.height(LettaDimens.Space.md))
        RailLabel("OUTPUT LOG")
        Spacer(Modifier.height(LettaDimens.Space.xs))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(LettaDimens.Radius.sm)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(LettaDimens.Space.md)) {
            Text(
                run.error ?: "No captured output for this run yet.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (run.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The back link at the top of a detail ("Overview" from a schedule, "Back" from a run). */
@Composable
private fun BackToOverview(description: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onBack)) {
        Icon(Icons.Outlined.ArrowBack, description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(LettaDimens.Control.icon))
        Spacer(Modifier.width(LettaDimens.Space.sm))
        Text("Overview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RailLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor, modifier = modifier)
}

/** A labelled value under a hairline (cadence, agent, when). */
@Composable
fun ScheduleDefRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(LettaDimens.Space.hair))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LettaDimens.Space.sm))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)))
    }
}

/** The rail's words, pure. */
object ScheduleRailText {
    const val CONTROL_NOTE = "Run now / Pause need a backend control endpoint (not available yet)."

    fun cadence(def: ScheduleDef): String =
        def.cron?.let { CronSchedule.parse(it)?.let(CronSchedule::describe) ?: it } ?: "One-time"

    fun relativeOrDash(now: Instant, next: Instant?): String =
        if (next != null) ScheduleFormat.relative(now, next) else "—"

    fun status(active: Boolean): String = if (active) "Active" else "Paused"

    fun nextRunHeadline(now: Instant, next: Instant?): String =
        if (next != null) "Runs ${ScheduleFormat.relative(now, next)}" else "No upcoming run"

    /** The schedule that fires soonest, or null when none has an upcoming run. */
    fun nextToFire(defs: List<ScheduleDef>, now: Instant): String? =
        defs.mapNotNull { def -> ScheduleProjection.nextRun(def, now)?.let { def.id to it } }.minByOrNull { it.second }?.first
}
