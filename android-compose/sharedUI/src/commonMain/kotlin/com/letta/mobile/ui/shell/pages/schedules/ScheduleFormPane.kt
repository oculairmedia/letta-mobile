package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.schedules.CronBuilder
import com.letta.mobile.data.schedules.CronBuilderState
import com.letta.mobile.data.schedules.CronCadence
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleFormDraft
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.host.PaneSize
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/**
 * The "New schedule" form (letta-mobile-ckmub.3.16): name, prompt and the cron builder with a live
 * preview of the next runs. A [LettaPaneKind.Modal]: a sheet on a phone, a dialog on a pointer.
 * [onSubmit] receives a complete draft only.
 */
@Composable
internal fun ScheduleFormPane(
    clock: ScheduleClock,
    onDismiss: () -> Unit,
    onSubmit: (ScheduleFormDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(ScheduleFormDraft()) }
    LettaPane(
        kind = LettaPaneKind.Modal,
        onDismiss = onDismiss,
        title = "New schedule",
        size = PaneSize(width = FormWidth),
        testTag = SchedulesPageTags.FORM,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                placeholder = { Text("Morning briefing") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(SchedulesPageTags.FORM_NAME),
            )
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = { draft = draft.copy(prompt = it) },
                label = { Text("Prompt") },
                placeholder = { Text("What should the agent do when this fires?") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag(SchedulesPageTags.FORM_PROMPT),
            )
            FieldLabel("WHEN")
            CadenceBuilder(draft.cadence) { draft = draft.copy(cadence = it) }
            CadencePreview(draft.cadence, clock)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = { onSubmit(draft) },
                enabled = draft.isComplete,
                modifier = Modifier.testTag(SchedulesPageTags.FORM_SUBMIT),
            ) { Text("Create schedule") }
        }
    }
}

private val FormWidth = 560.dp

/** Cadence presets, then the controls the chosen preset needs. */
@Composable
private fun CadenceBuilder(state: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        CADENCES.forEach { (cadence, label) ->
            CadenceChip(label, state.cadence == cadence) { onChange(state.copy(cadence = cadence)) }
        }
    }
    when (state.cadence) {
        CronCadence.EveryNMinutes -> Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            INTERVALS.forEach { n -> CadenceChip("$n min", state.intervalMinutes == n) { onChange(state.copy(intervalMinutes = n)) } }
        }
        CronCadence.Hourly -> Stepper(StepperSpec("Minute", state.minute, MINUTES) { onChange(state.copy(minute = it)) })
        CronCadence.Daily -> TimeOfDay(state, onChange)
        CronCadence.Weekly -> {
            TimeOfDay(state, onChange)
            WeekdayPicker(state, onChange)
        }
        CronCadence.Custom -> OutlinedTextField(
            value = state.customExpression,
            onValueChange = { onChange(state.copy(customExpression = it)) },
            label = { Text("Cron expression") },
            placeholder = { Text("*/15 * * * *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WeekdayPicker(state: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        (1..DAYS_IN_WEEK).forEach { iso ->
            val on = iso in state.daysOfWeek
            CadenceChip(ScheduleFormat.weekdayShort(iso), on) {
                onChange(state.copy(daysOfWeek = if (on) state.daysOfWeek - iso else state.daysOfWeek + iso))
            }
        }
    }
}

@Composable
private fun TimeOfDay(state: CronBuilderState, onChange: (CronBuilderState) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        Stepper(StepperSpec("Hour", state.hour, HOURS) { onChange(state.copy(hour = it)) })
        Stepper(StepperSpec("Minute", state.minute, MINUTES) { onChange(state.copy(minute = it)) })
        Text(ScheduleFormat.clockLabel(state.hour, state.minute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

private class StepperSpec(val label: String, val value: Int, val range: IntRange, val onChange: (Int) -> Unit)

/** A clamped − value + stepper (hour, minute). */
@Composable
private fun Stepper(spec: StepperSpec) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { spec.onChange((spec.value - 1).coerceAtLeast(spec.range.first)) }) {
            Icon(Icons.Outlined.Remove, contentDescription = "Decrease ${spec.label.lowercase()}")
        }
        Text(ScheduleFormat.pad2(spec.value), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        IconButton(onClick = { spec.onChange((spec.value + 1).coerceAtMost(spec.range.last)) }) {
            Icon(Icons.Outlined.Add, contentDescription = "Increase ${spec.label.lowercase()}")
        }
    }
}

/** What the cron reads as, and its next three runs. */
@Composable
private fun CadencePreview(state: CronBuilderState, clock: ScheduleClock) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(LettaDimens.Space.md).testTag(SchedulesPageTags.FORM_PREVIEW),
    ) {
        Text(CronBuilder.preview(state), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        CronBuilder.previewRuns(state, clock.now, clock.zone, PREVIEW_RUNS).forEach {
            Text(
                "• ${ScheduleFormat.dateLabel(it, clock.zone)} · ${ScheduleFormat.timeOfDay(it, clock.zone)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CadenceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    LettaChipTab(
        text = label,
        active = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = LettaDimens.Control.iconButtonSm).testTag(SchedulesPageTags.cadence(label)),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
}

private val CADENCES = listOf(
    CronCadence.EveryNMinutes to "Every N min",
    CronCadence.Hourly to "Hourly",
    CronCadence.Daily to "Daily",
    CronCadence.Weekly to "Weekly",
    CronCadence.Custom to "Custom",
)
private val INTERVALS = listOf(5, 10, 15, 30)
private val HOURS = 0..23
private val MINUTES = 0..59
private const val DAYS_IN_WEEK = 7
private const val PREVIEW_RUNS = 3
