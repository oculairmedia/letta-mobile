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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.letta.mobile.ui.theme.LettaDimens

// --- Create modal -----------------------------------------------------------

internal data class CreateScheduleClock(
    val now: Instant,
    val zone: TimeZone,
)

internal data class CreateScheduleModalParams(
    val clock: CreateScheduleClock,
    val canCreate: Boolean,
    val onDismiss: () -> Unit,
    val onCreate: (name: String, prompt: String, cron: String) -> Unit,
)

@Composable
internal fun CreateScheduleModal(params: CreateScheduleModalParams) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(CronBuilderState()) }
    // Modal opens on the empty name field, so claim focus rather than making the
    // user click into the first thing they need to fill in.
    val nameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocusRequester.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = params.onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(720.dp).clip(RoundedCornerShape(LettaDimens.Radius.lg)).background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LettaDimens.Radius.lg))
                .clickable(enabled = false) {}.padding(LettaDimens.Space.xl),
        ) {
            CreateScheduleModalHeader(onDismiss = params.onDismiss)
            Spacer(Modifier.height(LettaDimens.Space.lg))
            CreateScheduleNameField(
                name = name,
                onNameChange = { name = it },
                focusRequester = nameFocusRequester,
            )
            Spacer(Modifier.height(LettaDimens.Space.lg))
            CreateSchedulePromptField(prompt = prompt, onPromptChange = { prompt = it })
            Spacer(Modifier.height(LettaDimens.Space.lg))
            CreateScheduleWhenSection(
                CreateScheduleWhenParams(
                    draft = draft,
                    onDraftChange = { draft = it },
                    clock = params.clock,
                ),
            )
            Spacer(Modifier.height(LettaDimens.Space.xl))
            CreateScheduleModalActions(
                CreateScheduleModalActionsParams(
                    form = CreateScheduleFormState(name = name, prompt = prompt, draft = draft),
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
            modifier = Modifier.size(LettaDimens.Space.xl).clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun CreateScheduleNameField(
    name: String,
    onNameChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    Text("NAME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    Spacer(Modifier.height(LettaDimens.Space.sm))
    DesktopTextField(
        value = name,
        onValueChange = onNameChange,
        placeholder = "Morning briefing",
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

@Composable
private fun CreateSchedulePromptField(prompt: String, onPromptChange: (String) -> Unit) {
    Text("PROMPT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    Spacer(Modifier.height(LettaDimens.Space.sm))
    DesktopTextArea(
        value = prompt,
        onValueChange = onPromptChange,
        placeholder = "What should the agent do when this fires?",
        modifier = Modifier.fillMaxWidth().height(140.dp),
        decorationBoxModifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.md),
    )
}

private data class CreateScheduleWhenParams(
    val draft: CronBuilderState,
    val onDraftChange: (CronBuilderState) -> Unit,
    val clock: CreateScheduleClock,
)

@Composable
private fun CreateScheduleWhenSection(params: CreateScheduleWhenParams) {
    Text("WHEN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    Spacer(Modifier.height(LettaDimens.Space.sm))
    CadencePicker(params.draft, params.onDraftChange)
    Spacer(Modifier.height(LettaDimens.Space.md))
    CreateSchedulePreviewBox(draft = params.draft, clock = params.clock)
}

@Composable
private fun CreateSchedulePreviewBox(draft: CronBuilderState, clock: CreateScheduleClock) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(LettaDimens.Radius.sm)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(LettaDimens.Space.md)) {
        Column {
            Text(
                CronBuilder.preview(draft),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CronBuilder.previewRuns(draft, clock.now, clock.zone, 3).forEach {
                Text(
                    "• ${ScheduleFormat.dateLabel(it, clock.zone)} · ${ScheduleFormat.timeOfDay(it, clock.zone)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class CreateScheduleFormState(
    val name: String,
    val prompt: String,
    val draft: CronBuilderState,
)

private data class CreateScheduleModalActionsParams(
    val form: CreateScheduleFormState,
    val canCreate: Boolean,
    val onDismiss: () -> Unit,
    val onCreate: (name: String, prompt: String, cron: String) -> Unit,
)

@Composable
private fun CreateScheduleModalActions(params: CreateScheduleModalActionsParams) {
    val form = params.form
    val expression = CronBuilder.toExpression(form.draft)
    val valid = expression != null &&
        form.name.isNotBlank() &&
        form.prompt.isNotBlank() &&
        params.canCreate
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        DesktopOutlinedButton(onClick = params.onDismiss) { DesktopButtonContent("Cancel") }
        Spacer(Modifier.width(LettaDimens.Space.md))
        DesktopDefaultButton(
            onClick = {
                expression?.let { params.onCreate(form.name.trim(), form.prompt.trim(), it) }
            },
            enabled = valid,
        ) {
            DesktopButtonContent(text = "Create schedule")
        }
    }
    if (!params.canCreate) {
        Spacer(Modifier.height(LettaDimens.Space.sm))
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
        CadenceTypeRow(draft = draft, onChange = onChange)
        Spacer(Modifier.height(LettaDimens.Space.md))
        CadenceDetails(draft = draft, onChange = onChange)
    }
}

@Composable
private fun CadenceTypeRow(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        cadenceChip("Every N min", draft.cadence == CronCadence.EveryNMinutes) {
            onChange(draft.copy(cadence = CronCadence.EveryNMinutes))
        }
        cadenceChip("Hourly", draft.cadence == CronCadence.Hourly) {
            onChange(draft.copy(cadence = CronCadence.Hourly))
        }
        cadenceChip("Daily", draft.cadence == CronCadence.Daily) {
            onChange(draft.copy(cadence = CronCadence.Daily))
        }
        cadenceChip("Weekly", draft.cadence == CronCadence.Weekly) {
            onChange(draft.copy(cadence = CronCadence.Weekly))
        }
        cadenceChip("Custom", draft.cadence == CronCadence.Custom) {
            onChange(draft.copy(cadence = CronCadence.Custom))
        }
    }
}

@Composable
private fun CadenceDetails(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    when (draft.cadence) {
        CronCadence.EveryNMinutes -> EveryNMinutesRow(draft = draft, onChange = onChange)
        CronCadence.Hourly -> TimeRow(
            TimeRowParams("Minute", draft.minute, 0..59) { onChange(draft.copy(minute = it)) },
        )
        CronCadence.Daily -> TimeOfDayRow(draft) { onChange(it) }
        CronCadence.Weekly -> WeeklyCadenceDetails(draft = draft, onChange = onChange)
        CronCadence.Custom -> DesktopTextField(
            value = draft.customExpression,
            onValueChange = { onChange(draft.copy(customExpression = it)) },
            placeholder = "*/15 * * * *",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EveryNMinutesRow(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        listOf(5, 10, 15, 30).forEach { n ->
            cadenceChip("$n", draft.intervalMinutes == n) { onChange(draft.copy(intervalMinutes = n)) }
        }
    }
}

@Composable
private fun WeeklyCadenceDetails(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Column {
        TimeOfDayRow(draft) { onChange(it) }
        Spacer(Modifier.height(LettaDimens.Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            (1..7).forEach { iso ->
                val on = iso in draft.daysOfWeek
                cadenceChip(ScheduleFormat.weekdayShort(iso).take(1), on) {
                    onChange(draft.copy(daysOfWeek = if (on) draft.daysOfWeek - iso else draft.daysOfWeek + iso))
                }
            }
        }
    }
}

@Composable
internal fun TimeOfDayRow(draft: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        TimeRow(TimeRowParams("Hour", draft.hour, 0..23) { onChange(draft.copy(hour = it)) })
        TimeRow(TimeRowParams("Minute", draft.minute, 0..59) { onChange(draft.copy(minute = it)) })
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
    val range: IntRange,
    val onChange: (Int) -> Unit,
)

@Composable
internal fun TimeRow(params: TimeRowParams) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        IconBtn(Icons.Outlined.ChevronLeft, "−") {
            params.onChange((params.value - 1).coerceAtLeast(params.range.first))
        }
        Text(
            ScheduleFormat.pad2(params.value),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconBtn(Icons.Outlined.ChevronRight, "+") {
            params.onChange((params.value + 1).coerceAtMost(params.range.last))
        }
    }
}

// --- Shared bits ------------------------------------------------------------

@Composable
internal fun DefRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Spacer(Modifier.height(LettaDimens.Space.hair))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LettaDimens.Space.sm))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)))
    }
}

@Composable
internal fun cadenceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(LettaDimens.Radius.sm))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(LettaDimens.Space.xxl).clip(RoundedCornerShape(LettaDimens.Radius.sm)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(LettaDimens.Control.icon))
    }
}

@Composable
internal fun ScheduleEmptyState(canCreate: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.customColors.onSurfaceMutedColor, modifier = Modifier.size(LettaDimens.Orb.lg))
        Spacer(Modifier.height(LettaDimens.Space.md))
        Text("No schedules yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (canCreate) "Use “New schedule” to create one." else "Schedules created elsewhere will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
        )
    }
}

internal fun fullMonth(monthNumber: Int): String =
    listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )[(monthNumber - 1).coerceIn(0, 11)]
