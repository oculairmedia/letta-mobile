package com.letta.mobile.ui.a2ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.a2ui.A2uiBindingResolver
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiResolvedBinding
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


@Composable
internal fun A2uiScheduleCard(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
    renderScope: A2uiRenderScope,
) {
    val props = component.scheduleCardProps(surface, renderScope)
    if (props == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.MISSING_COMPONENT))
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Schedule ${props.name}, ${props.status.label}"
                props.nextRun?.let { stateDescription = "Next run $it" }
            }
            .testTag(A2uiTestTags.SCHEDULE_CARD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = props.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    props.agentName?.takeIf { it.isNotBlank() }?.let { agent ->
                        Text(
                            text = agent,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ScheduleStatusPill(props.status)
            }

            props.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScheduleMetaLine("Schedule", props.scheduleText)
                props.nextRun?.takeIf { it.isNotBlank() }?.let { ScheduleMetaLine("Next", it) }
                props.lastRun?.takeIf { it.isNotBlank() }?.let { ScheduleMetaLine("Last", it) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(scheduleIdAction(surface, ScheduleRunNowAction, props.id)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Run now")
                }
                OutlinedButton(
                    onClick = {
                        onAction(
                            scheduleIdAction(
                                surface = surface,
                                name = if (props.paused) ScheduleResumeAction else SchedulePauseAction,
                                id = props.id,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (props.paused) "Resume" else "Pause")
                }
                TextButton(
                    onClick = { onAction(scheduleIdAction(surface, ScheduleDeleteAction, props.id)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
internal fun ScheduleStatusPill(status: ScheduleStatus) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when (status) {
            ScheduleStatus.Active -> MaterialTheme.colorScheme.tertiaryContainer
            ScheduleStatus.Paused -> MaterialTheme.colorScheme.secondaryContainer
            ScheduleStatus.Failed -> MaterialTheme.colorScheme.errorContainer
            ScheduleStatus.Idle -> MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = when (status) {
                ScheduleStatus.Active -> MaterialTheme.colorScheme.onTertiaryContainer
                ScheduleStatus.Paused -> MaterialTheme.colorScheme.onSecondaryContainer
                ScheduleStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
                ScheduleStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun ScheduleMetaLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 48.dp, max = 72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun A2uiScheduleSelectorInput(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val label = component.resolveControlLabel(surface, renderScope) ?: "Schedule"
    val path = (component.raw["value"] ?: component.raw["schedule"]).bindingPath()?.let(renderScope::resolvePath)
    val boundValue = path?.let { scheduleSelectorValueAt(surface, it) } ?: ScheduleSelectorValue(
        mode = ScheduleSelectorMode.Cron,
        value = "",
    )
    var localMode by remember(component.id) { mutableStateOf(boundValue.mode) }
    var localValue by remember(component.id) { mutableStateOf(boundValue.value) }
    var expanded by remember(component.id) { mutableStateOf(false) }
    val mode = if (path != null) boundValue.mode else localMode
    val value = if (path != null) boundValue.value else localValue

    fun update(nextMode: ScheduleSelectorMode = mode, nextValue: String = value) {
        if (path != null) {
            surface.dataModel.applyPatch(path, scheduleSelectorJson(nextMode, nextValue))
        } else {
            localMode = nextMode
            localValue = nextValue
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(A2uiTestTags.SCHEDULE_SELECTOR_INPUT),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!surfaceSubmitting) expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = mode.label,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                label = { Text("Mode") },
                readOnly = true,
                enabled = !surfaceSubmitting,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                ScheduleSelectorMode.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            update(nextMode = option, nextValue = option.defaultValue(""))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { update(nextValue = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(mode.valueLabel) },
            placeholder = { Text(mode.placeholder) },
            readOnly = surfaceSubmitting,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (mode == ScheduleSelectorMode.Every) KeyboardType.Number else KeyboardType.Text,
            ),
        )
        Text(
            text = mode.preview(value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                onAction(
                    scheduleSaveAction(
                        surface = surface,
                        id = component.scheduleSelectorId(surface, renderScope),
                        agentId = component.scheduleSelectorAgentId(surface, renderScope),
                        message = component.scheduleSelectorMessage(surface, renderScope),
                        selector = scheduleSelectorJson(mode, value),
                    )
                )
            },
            enabled = !surfaceSubmitting && value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(resolveBindingText(component.raw["submitLabel"], surface, renderScope) ?: "Save schedule")
        }
    }
}

@Composable
internal fun A2uiComponent.scheduleCardProps(
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
): ScheduleCardProps? {
    val id = resolveBindingText(raw["scheduleId"] ?: raw["id"], surface, renderScope)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val type = resolveBindingText(raw["type"] ?: raw["scheduleType"], surface, renderScope).orEmpty()
    val cron = resolveBindingText(raw["cron"] ?: raw["cronExpression"], surface, renderScope)
    val every = resolveBindingText(raw["every"], surface, renderScope)
    val at = resolveBindingText(raw["at"] ?: raw["scheduledAt"], surface, renderScope)
    val nextRun = resolveBindingText(raw["nextRun"] ?: raw["nextScheduledTime"], surface, renderScope)
    val statusText = resolveBindingText(raw["status"], surface, renderScope).orEmpty()
    val status = ScheduleStatus.from(statusText)
    return ScheduleCardProps(
        id = id,
        name = resolveBindingText(raw["name"] ?: raw["title"], surface, renderScope)
            ?.takeIf { it.isNotBlank() }
            ?: "Schedule $id",
        agentName = resolveBindingText(raw["agent"] ?: raw["agentName"], surface, renderScope),
        status = status,
        summary = resolveBindingText(raw["summary"] ?: raw["message"] ?: raw["description"], surface, renderScope),
        scheduleText = scheduleSummary(type = type, cron = cron, every = every, at = at, nextRun = nextRun),
        nextRun = nextRun,
        lastRun = resolveBindingText(raw["lastRun"] ?: raw["lastRunAt"], surface, renderScope),
        paused = status == ScheduleStatus.Paused || raw.booleanValue("paused") == true,
    )
}

@Composable
internal fun scheduleSelectorValueAt(
    surface: A2uiSurfaceState,
    path: String,
): ScheduleSelectorValue {
    val value by surface.dataModel.observe(path)
    val obj = value as? JsonObject
    return ScheduleSelectorValue(
        mode = ScheduleSelectorMode.from(obj?.stringValue("mode", "type")),
        value = obj?.stringValue("value", "cron", "every", "at", "cron_expression", "scheduled_at").orEmpty(),
    )
}

internal fun A2uiComponent.scheduleSelectorId(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    A2uiBindingResolver.resolve((raw["id"] ?: raw["scheduleId"])?.withScopedPaths(renderScope), surface.dataModel)
        .let { it as? A2uiResolvedBinding.Value }
        ?.value
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }

internal fun A2uiComponent.scheduleSelectorAgentId(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    A2uiBindingResolver.resolve(raw["agentId"]?.withScopedPaths(renderScope), surface.dataModel)
        .let { it as? A2uiResolvedBinding.Value }
        ?.value
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }

internal fun A2uiComponent.scheduleSelectorMessage(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String =
    A2uiBindingResolver.resolve((raw["message"] ?: raw["content"])?.withScopedPaths(renderScope), surface.dataModel)
        .let { it as? A2uiResolvedBinding.Value }
        ?.value
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }
        ?: ""

internal fun scheduleSummary(
    type: String,
    cron: String?,
    every: String?,
    at: String?,
    nextRun: String?,
): String = when {
    cron?.isNotBlank() == true || type == "cron" || type == "recurring" -> "Cron ${cron.orEmpty()}".trim()
    every?.isNotBlank() == true -> "Every $every"
    at?.isNotBlank() == true || type == "at" || type == "one-time" -> "At ${at ?: nextRun.orEmpty()}".trim()
    nextRun?.isNotBlank() == true -> "Next $nextRun"
    else -> "Schedule pending"
}

internal fun scheduleSelectorJson(mode: ScheduleSelectorMode, value: String): JsonObject =
    buildJsonObject {
        put("mode", mode.wireValue)
        put("type", mode.scheduleType)
        put("value", value)
        when (mode) {
            ScheduleSelectorMode.Cron -> put("cron_expression", value)
            ScheduleSelectorMode.Every -> put("every", value)
            ScheduleSelectorMode.At -> put("scheduled_at", value)
        }
    }

internal fun JsonObject.toScheduleDefinitionJson(): JsonObject {
    val mode = ScheduleSelectorMode.from(stringValue("mode", "type"))
    val value = stringValue("value", "cron_expression", "every", "scheduled_at").orEmpty()
    return buildJsonObject {
        put("type", mode.scheduleType)
        when (mode) {
            ScheduleSelectorMode.Cron -> put("cron_expression", value)
            ScheduleSelectorMode.Every -> put("cron_expression", "*/$value * * * *")
            ScheduleSelectorMode.At -> put("scheduled_at", value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value))
        }
    }
}

internal fun scheduleIdAction(
    surface: A2uiSurfaceState,
    name: String,
    id: String,
): A2uiAction {
    val context = buildJsonObject { put("id", id) }
    return scheduleAction(surface = surface, name = name, actionId = id, context = context)
}

internal fun scheduleSaveAction(
    surface: A2uiSurfaceState,
    id: String?,
    agentId: String?,
    message: String,
    selector: JsonObject,
): A2uiAction {
    val createParams = buildJsonObject {
        agentId?.let { put("agent_id", it) }
        put(
            "messages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", message)
                    }
                )
            )
        )
        put("schedule", selector.toScheduleDefinitionJson())
    }
    val context = buildJsonObject {
        id?.let { put("id", it) }
        put("create_params", createParams)
    }
    return scheduleAction(surface = surface, name = ScheduleSaveAction, actionId = id, context = context)
}

internal fun scheduleAction(
    surface: A2uiSurfaceState,
    name: String,
    actionId: String?,
    context: JsonObject,
): A2uiAction {
    val raw = buildJsonObject {
        put("actionName", name)
        put("name", name)
        put("surfaceId", surface.surfaceId)
        put("context", context)
        surface.conversationId?.let { put("conversationId", it) }
        surface.runId?.let { put("runId", it) }
        surface.turnId?.let { put("turnId", it) }
        actionId?.let { put("actionId", it) }
    }
    return A2uiAction(
        name = name,
        surfaceId = surface.surfaceId,
        context = context,
        conversationId = surface.conversationId,
        runId = surface.runId,
        turnId = surface.turnId,
        actionId = actionId,
        raw = raw,
    )
}
