package com.letta.mobile.desktop.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CronBuilder
import com.letta.mobile.data.schedules.CronBuilderState
import com.letta.mobile.data.schedules.CronCadence
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.ScheduleRun
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.ui.theme.customColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlin.time.Instant

// --- Create modal -----------------------------------------------------------

internal data class CreateScheduleModalParams(
    val now: Instant,
    val zone: TimeZone,
    val canCreate: Boolean,
    val onDismiss: () -> Unit,
    val onCreate: (name: String, prompt: String, cron: String) -> Unit,
)

@Composable
internal fun CreateScheduleModal(params: CreateScheduleModalParams) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(CronBuilderState()) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = params.onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(720.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}.padding(24.dp),
        ) {
            CreateScheduleModalHeader(onDismiss = params.onDismiss)
            Spacer(Modifier.height(16.dp))
            CreateScheduleNameField(name = name, onNameChange = { name = it })
            Spacer(Modifier.height(14.dp))
            CreateSchedulePromptField(prompt = prompt, onPromptChange = { prompt = it })
            Spacer(Modifier.height(14.dp))
            CreateScheduleWhenSection(
                CreateScheduleWhenParams(
                    draft = draft,
                    onDraftChange = { draft = it },
                    now = params.now,
                    zone = params.zone,
                ),
            )
            Spacer(Modifier.height(20.dp))
            CreateScheduleModalActions(
                CreateScheduleModalActionsParams(
                    name = name,
                    prompt = prompt,
                    draft = draft,
                    canCreate = params.canCreate,
                    onDismiss = params.onDismiss,
                    onCreate = params.onCreate,
                ),
            )
        }
    }
}

@Composable
private fun CreateScheduleModalHeader(onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "New schedule",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.Close,
            "Close",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun CreateScheduleNameField(name: String, onNameChange: (String) -> Unit) {
    Text("NAME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    Spacer(Modifier.height(6.dp))
    DesktopTextField(value = name, onValueChange = onNameChange, placeholder = "Morning briefing", modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CreateSchedulePromptField(prompt: String, onPromptChange: (String) -> Unit) {
    Text("PROMPT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    Spacer(Modifier.height(6.dp))
    DesktopTextArea(
        value = prompt,
        onValueChange = onPromptChange,
        placeholder = "What should the agent do when this fires?",
        modifier = Modifier.fillMaxWidth().height(140.dp),
        decorationBoxModifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private data class CreateScheduleWhenParams(
    val draft: CronBuilderState,
    val onDraftChange: (CronBuilderState) -> Unit,
    val now: Instant,
    val zone: TimeZone,
)

@Composable
private fun CreateScheduleWhenSection(params: CreateScheduleWhenParams) {
    Text("WHEN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    Spacer(Modifier.height(6.dp))
    CadencePicker(params.draft, params.onDraftChange)
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) {
        Column {
            Text(
                CronBuilder.preview(params.draft),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CronBuilder.previewRuns(params.draft, params.now, params.zone, 3).forEach {
                Text(
                    "• ${ScheduleFormat.dateLabel(it, params.zone)} · ${ScheduleFormat.timeOfDay(it, params.zone)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class CreateScheduleModalActionsParams(
    val name: String,
    val prompt: String,
    val draft: CronBuilderState,
    val canCreate: Boolean,
    val onDismiss: () -> Unit,
    val onCreate: (name: String, prompt: String, cron: String) -> Unit,
)

@Composable
private fun CreateScheduleModalActions(params: CreateScheduleModalActionsParams) {
    val expression = CronBuilder.toExpression(params.draft)
    val valid = expression != null &&
        params.name.isNotBlank() &&
        params.prompt.isNotBlank() &&
        params.canCreate
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        DesktopOutlinedButton(onClick = params.onDismiss) { DesktopButtonContent("Cancel") }
        Spacer(Modifier.width(10.dp))
        DesktopDefaultButton(
            onClick = {
                expression?.let { params.onCreate(params.name.trim(), params.prompt.trim(), it) }
            },
            enabled = valid,
        ) {
            DesktopButtonContent(text = "Create schedule")
        }
    }
    if (!params.canCreate) {
        Spacer(Modifier.height(6.dp))
        Text(
            "This backend doesn't allow creating schedules.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun CadencePicker(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cadenceChip("Every N min", draft.cadence == CronCadence.EveryNMinutes) { onChange(draft.copy(cadence = CronCadence.EveryNMinutes)) }
            cadenceChip("Hourly", draft.cadence == CronCadence.Hourly) { onChange(draft.copy(cadence = CronCadence.Hourly)) }
            cadenceChip("Daily", draft.cadence == CronCadence.Daily) { onChange(draft.copy(cadence = CronCadence.Daily)) }
            cadenceChip("Weekly", draft.cadence == CronCadence.Weekly) { onChange(draft.copy(cadence = CronCadence.Weekly)) }
            cadenceChip("Custom", draft.cadence == CronCadence.Custom) { onChange(draft.copy(cadence = CronCadence.Custom)) }
        }
        Spacer(Modifier.height(10.dp))
        CadenceDetails(draft = draft, onChange = onChange)
    }
}

@Composable
private fun CadenceDetails(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    when (draft.cadence) {
        CronCadence.EveryNMinutes -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 30).forEach { n ->
                cadenceChip("$n", draft.intervalMinutes == n) { onChange(draft.copy(intervalMinutes = n)) }
            }
        }
        CronCadence.Hourly -> TimeRow(TimeRowParams("Minute", draft.minute, 0, 59) { onChange(draft.copy(minute = it)) })
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
        CronCadence.Custom -> DesktopTextField(
            value = draft.customExpression,
            onValueChange = { onChange(draft.copy(customExpression = it)) },
            placeholder = "*/15 * * * *",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun TimeOfDayRow(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeRow(TimeRowParams("Hour", draft.hour, 0, 23) { onChange(draft.copy(hour = it)) })
        TimeRow(TimeRowParams("Minute", draft.minute, 0, 59) { onChange(draft.copy(minute = it)) })
        Text(
            ScheduleFormat.clockLabel(draft.hour, draft.minute),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
    }
}

internal data class TimeRowParams(
    val label: String,
    val value: Int,
    val min: Int,
    val max: Int,
    val onChange: (Int) -> Unit,
)

@Composable
internal fun TimeRow(params: TimeRowParams) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconBtn(Icons.Outlined.ChevronLeft, "−", { params.onChange((params.value - 1).coerceAtLeast(params.min)) })
        Text(
            ScheduleFormat.pad2(params.value),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconBtn(Icons.Outlined.ChevronRight, "+", { params.onChange((params.value + 1).coerceAtMost(params.max)) })
    }
}

// --- Shared bits ------------------------------------------------------------

@Composable
internal fun DefRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
    }
}

@Composable
internal fun cadenceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ScheduleEmptyState(canCreate: Boolean) {
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
internal fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.Done -> MaterialTheme.customColors.successColor
    RunStatus.Failed -> MaterialTheme.colorScheme.error
    RunStatus.Running, RunStatus.Next -> MaterialTheme.customColors.runningColor
    RunStatus.Upcoming -> MaterialTheme.colorScheme.outline
}

@Composable
internal fun agendaStatusLabel(run: ScheduleRun, now: Instant): Pair<String, Color> = when (run.status) {
    RunStatus.Done -> "Ran" to MaterialTheme.customColors.successColor
    RunStatus.Failed -> "Failed" to MaterialTheme.colorScheme.error
    RunStatus.Running -> "running" to MaterialTheme.customColors.runningColor
    RunStatus.Next -> ScheduleFormat.relative(now, run.instant) to MaterialTheme.customColors.runningColor
    RunStatus.Upcoming -> "scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun monthDayLabel(date: LocalDate): String =
    "${ScheduleFormat.monthShort(date.month.ordinal + 1)} ${date.day}"

internal fun dayHeading(date: LocalDate, today: LocalDate): String {
    val delta = (date.toEpochDays() - today.toEpochDays()).toInt()
    return when (delta) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> "${fullWeekday(date.dayOfWeek.isoDayNumber)}, ${monthDayLabel(date)}"
    }
}

internal fun fullWeekday(iso: Int): String =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[(iso - 1).coerceIn(0, 6)]

internal fun fullMonth(monthNumber: Int): String =
    listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )[(monthNumber - 1).coerceIn(0, 11)]
