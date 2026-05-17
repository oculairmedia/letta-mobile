package com.letta.mobile.ui.a2ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.letta.mobile.data.a2ui.A2uiBindingResolver
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiResolvedBinding
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.a2ui.resolveA2uiActionContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun A2uiRenderer(
    surfaceId: String,
    surfaceManager: A2uiSurfaceManager,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit = {},
) {
    val surfaces by surfaceManager.surfaces.collectAsState()
    A2uiSurfaceRenderer(
        surface = surfaces[surfaceId],
        modifier = modifier,
        onAction = onAction,
    )
}

@Composable
fun A2uiSurfaceRenderer(
    surface: A2uiSurfaceState?,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit = {},
) {
    if (surface == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.SurfaceMissing))
        return
    }

    val root = surface.rootComponentId
        ?.let(surface.components::get)
        ?: surface.components.values.firstOrNull()

    if (root == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.SurfacePending))
        return
    }

    A2uiComponentNode(
        component = root,
        surface = surface,
        modifier = modifier,
        visited = emptySet(),
        onAction = onAction,
    )
}

object A2uiTestTags {
    const val SurfaceMissing = "a2ui_surface_missing"
    const val SurfacePending = "a2ui_surface_pending"
    const val MissingComponent = "a2ui_missing_component"
    const val MissingText = "a2ui_missing_text"
    const val MissingImage = "a2ui_missing_image"
    const val TextField = "a2ui_text_field"
    const val DateTimeInput = "a2ui_date_time_input"
    const val Divider = "a2ui_divider"
    const val ToolApprovalCard = "a2ui_tool_approval_card"
    const val ToolApprovalSensitiveValue = "a2ui_tool_approval_sensitive_value"
    const val ToolApprovalCountdown = "a2ui_tool_approval_countdown"
}

@Composable
private fun A2uiComponentNode(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
) {
    if (component.id in visited || visited.size > MaxRenderDepth) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }
    val nextVisited = visited + component.id
    when (component.component) {
        "Text" -> A2uiText(component = component, surface = surface, modifier = modifier)
        "TextField" -> A2uiTextField(component = component, surface = surface, modifier = modifier)
        "DateTimeInput" -> A2uiDateTimeInput(component = component, surface = surface, modifier = modifier)
        "Image" -> A2uiImage(component = component, surface = surface, modifier = modifier)
        "Divider" -> A2uiDivider(component = component, modifier = modifier)
        "ToolApprovalCard" -> A2uiToolApprovalCard(
            component = component,
            surface = surface,
            modifier = modifier,
            onAction = onAction,
        )
        "Column" -> A2uiColumn(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
        )
        "Row" -> A2uiRow(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
        )
        "Card" -> A2uiCard(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
        )
        "Button" -> A2uiButton(
            component = component,
            surface = surface,
            modifier = modifier,
            onAction = onAction,
        )
        else -> A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
    }
}

@Composable
private fun A2uiToolApprovalCard(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
) {
    val props = remember(component.raw) { component.toolApprovalProps() }
    if (props == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    val riskStyle = props.risk.style()
    val haptic = LocalHapticFeedback.current
    var result by remember(component.id, props.callId) { mutableStateOf<ToolApprovalResult?>(null) }
    var remainingSeconds by remember(component.id, props.callId, props.timeoutSeconds) {
        mutableStateOf(props.timeoutSeconds)
    }
    var argumentsExpanded by remember(component.id, props.callId) { mutableStateOf(true) }
    var revealedSensitiveKeys by remember(component.id, props.callId) { mutableStateOf(emptySet<String>()) }

    fun dispatch(affordance: ToolApprovalAffordance) {
        if (result != null) return
        val next = ToolApprovalResult(
            decision = affordance.decision,
            scope = affordance.scope,
        )
        result = next
        if (props.risk == ToolApprovalRisk.Destructive || affordance == ToolApprovalAffordance.Deny) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        onAction(toolApprovalAction(surface.surfaceId, props.callId, next))
    }

    LaunchedEffect(component.id, props.callId, props.timeoutSeconds, result) {
        if (result != null || props.timeoutSeconds <= 0) return@LaunchedEffect
        while (remainingSeconds > 0 && result == null) {
            delay(1_000)
            remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0)
        }
        if (remainingSeconds == 0 && result == null) {
            val timeout = ToolApprovalResult(decision = "timeout", scope = "timeout")
            result = timeout
            onAction(toolApprovalAction(surface.surfaceId, props.callId, timeout))
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Tool approval for ${props.toolName}, ${props.risk.label} risk"
                stateDescription = result?.statusLabel() ?: "Awaiting approval"
            }
            .testTag(A2uiTestTags.ToolApprovalCard),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(riskStyle.borderWidth, riskStyle.borderColor),
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
                        text = props.toolName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    props.toolDescription?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ToolApprovalRiskPill(props.risk, riskStyle)
            }

            props.rationale?.takeIf { it.isNotBlank() }?.let { rationale ->
                Text(
                    text = rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (props.arguments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Arguments",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { argumentsExpanded = !argumentsExpanded }) {
                            Text(if (argumentsExpanded) "Hide" else "Show")
                        }
                    }
                    if (argumentsExpanded) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                props.arguments.forEach { argument ->
                                    ToolApprovalArgumentRow(
                                        argument = argument,
                                        revealed = argument.key in revealedSensitiveKeys,
                                        onReveal = {
                                            revealedSensitiveKeys = revealedSensitiveKeys + argument.key
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ToolApprovalStatusLine(
                result = result,
                remainingSeconds = remainingSeconds,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                props.affordances.chunked(ToolApprovalButtonsPerRow).forEach { rowAffordances ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowAffordances.forEach { affordance ->
                            ToolApprovalAffordanceButton(
                                affordance = affordance,
                                risk = props.risk,
                                enabled = result == null,
                                modifier = Modifier.weight(1f),
                                onClick = { dispatch(affordance) },
                            )
                        }
                        if (rowAffordances.size < ToolApprovalButtonsPerRow) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolApprovalRiskPill(
    risk: ToolApprovalRisk,
    style: ToolApprovalRiskStyle,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = style.pillContainer,
    ) {
        Text(
            text = risk.label,
            style = MaterialTheme.typography.labelSmall,
            color = style.pillContent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ToolApprovalArgumentRow(
    argument: ToolApprovalArgument,
    revealed: Boolean,
    onReveal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = argument.key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 72.dp, max = 96.dp),
        )
        val valueModifier = if (argument.isSensitive && !revealed) {
            Modifier
                .clickable(onClickLabel = "Reveal value for ${argument.key}") { onReveal() }
                .testTag(A2uiTestTags.ToolApprovalSensitiveValue)
        } else {
            Modifier
        }
        Text(
            text = if (argument.isSensitive && !revealed) SensitiveMask else argument.value,
            modifier = valueModifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (revealed) 4 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolApprovalStatusLine(
    result: ToolApprovalResult?,
    remainingSeconds: Int,
) {
    val text = result?.statusLabel() ?: "Auto-denies in ${remainingSeconds}s"
    Text(
        text = text,
        modifier = Modifier.testTag(A2uiTestTags.ToolApprovalCountdown),
        style = MaterialTheme.typography.labelMedium,
        color = when (result?.decision) {
            "deny", "timeout" -> MaterialTheme.colorScheme.error
            "approve" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun ToolApprovalAffordanceButton(
    affordance: ToolApprovalAffordance,
    risk: ToolApprovalRisk,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val buttonModifier = modifier.widthIn(min = 64.dp)
    if (affordance == ToolApprovalAffordance.Deny) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(affordance.label)
        }
    } else {
        val colors = if (risk == ToolApprovalRisk.Destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            colors = colors,
        ) {
            Text(affordance.label)
        }
    }
}

@Composable
private fun A2uiText(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
) {
    val text = component.resolveText(surface)
    if (text == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingText))
        return
    }
    Text(
        text = text,
        modifier = modifier,
        style = component.textStyle(),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun A2uiTextField(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
) {
    val binding = component.raw["value"] ?: component.raw["text"]
    val path = binding.bindingPath()
    val value = component.resolveInputValue(surface, binding)
    val label = resolveBindingText(component.raw["label"], surface)
    val placeholder = resolveBindingText(component.raw["placeholder"], surface)
    val fieldType = component.raw.stringValue("textFieldType", "variant", "type").orEmpty()
    val validation = component.raw.stringValue("validationRegexp")
    val isError = validation != null && value.isNotBlank() && !value.matchesValidation(validation)

    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            path?.let {
                surface.dataModel.applyPatch(
                    path = it,
                    value = component.inputValue(next, fieldType),
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(A2uiTestTags.TextField),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        isError = isError,
        supportingText = if (isError) {
            { Text("Invalid value") }
        } else {
            null
        },
        singleLine = fieldType != "longText",
        minLines = if (fieldType == "longText") 3 else 1,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (fieldType) {
                "number" -> KeyboardType.Number
                "obscured" -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        visualTransformation = if (fieldType == "obscured") {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
    )
}

@Composable
private fun A2uiDateTimeInput(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
) {
    val binding = component.raw["value"] ?: component.raw["text"]
    val path = binding.bindingPath()
    val value = component.resolveInputValue(surface, binding)
    val label = resolveBindingText(component.raw["label"], surface) ?: "Date and time"
    val enableDate = component.raw.booleanValue("enableDate") ?: true
    val enableTime = component.raw.booleanValue("enableTime") ?: false
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf(value.toDateMillis()) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = value.toDateMillis())
    val initialTime = value.toLocalTime() ?: LocalTime.NOON
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = true,
    )

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            placeholder = { Text(component.dateTimePlaceholder(enableDate, enableTime)) },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable {
                    if (enableDate) {
                        showDatePicker = true
                    } else {
                        showTimePicker = true
                    }
                }
                .testTag(A2uiTestTags.DateTimeInput),
        )
    }

    if (showDatePicker) {
        AlertDialog(
            onDismissRequest = { showDatePicker = false },
            title = { Text("Select date") },
            text = {
                DatePicker(state = datePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDateMillis = datePickerState.selectedDateMillis ?: pendingDateMillis
                        showDatePicker = false
                        if (enableTime) {
                            showTimePicker = true
                        } else {
                            path?.let {
                                surface.dataModel.applyPatch(
                                    path = it,
                                    value = JsonPrimitive(formatDateTime(pendingDateMillis, null, enableDate, false)),
                                )
                            }
                        }
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select time") },
            text = { TimeInput(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        path?.let {
                            surface.dataModel.applyPatch(
                                path = it,
                                value = JsonPrimitive(
                                    formatDateTime(
                                        dateMillis = pendingDateMillis,
                                        time = LocalTime.of(timePickerState.hour, timePickerState.minute),
                                        enableDate = enableDate,
                                        enableTime = enableTime,
                                    ),
                                ),
                            )
                        }
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun A2uiImage(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
) {
    val imageUrl = resolveBindingText(component.raw["url"] ?: component.raw["src"], surface)
    if (imageUrl.isNullOrBlank()) {
        A2uiSkeletonImage(modifier = modifier.testTag(A2uiTestTags.MissingImage))
        return
    }

    val context = LocalContext.current
    val request = remember(context, imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey("a2ui-image:$imageUrl")
            .diskCacheKey("a2ui-image:$imageUrl")
            .build()
    }
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    val error = ColorPainter(MaterialTheme.colorScheme.errorContainer)
    AsyncImage(
        model = request,
        contentDescription = resolveBindingText(
            component.raw["alt"] ?: component.raw["contentDescription"],
            surface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = component.height() ?: 96.dp)
            .then(component.aspectRatioModifier()),
        placeholder = placeholder,
        error = error,
        contentScale = component.contentScale(),
    )
}

@Composable
private fun A2uiDivider(
    component: A2uiComponent,
    modifier: Modifier = Modifier,
) {
    if (component.raw.stringValue("axis", "orientation") == "vertical") {
        VerticalDivider(
            modifier = modifier
                .height(component.height() ?: 32.dp)
                .testTag(A2uiTestTags.Divider),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    } else {
        HorizontalDivider(
            modifier = modifier
                .fillMaxWidth()
                .testTag(A2uiTestTags.Divider),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun A2uiColumn(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
) {
    val children = component.children
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(component.spacing()),
    ) {
        if (children.isEmpty()) {
            A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
        }
        children.forEach { childId ->
            val child = surface.components[childId]
            if (child == null) {
                A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
            } else {
                A2uiComponentNode(
                    component = child,
                    surface = surface,
                    visited = visited,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun A2uiRow(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
) {
    val children = component.children
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = component.horizontalArrangement(),
        verticalAlignment = component.verticalAlignment(),
    ) {
        if (children.isEmpty()) {
            A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
        }
        children.forEach { childId ->
            val child = surface.components[childId]
            if (child == null) {
                A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
            } else {
                A2uiComponentNode(
                    component = child,
                    surface = surface,
                    modifier = if (component.weightRowChildren()) Modifier.weight(1f) else Modifier,
                    visited = visited,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun A2uiCard(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(component.cornerRadius()),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = component.elevation()),
    ) {
        val child = component.child ?: component.children.firstOrNull()
        Box(modifier = Modifier.padding(16.dp)) {
            if (child == null) {
                A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
            } else {
                val childComponent = surface.components[child]
                if (childComponent == null) {
                    A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
                } else {
                    A2uiComponentNode(
                        component = childComponent,
                        surface = surface,
                        visited = visited,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun A2uiButton(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
) {
    val label = component.resolveButtonLabel(surface)
    val action = component.action(surface)
    Button(
        onClick = { component.action(surface)?.let(onAction) },
        enabled = label != null && action != null,
        modifier = modifier,
    ) {
        if (label == null) {
            A2uiSkeletonLine(
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .testTag(A2uiTestTags.MissingText),
                height = 12.dp,
            )
        } else {
            Text(text = label)
        }
    }
}

@Composable
private fun A2uiSkeletonCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            A2uiSkeletonLine(widthFraction = 0.65f)
            A2uiSkeletonLine(widthFraction = 1f)
            A2uiSkeletonLine(widthFraction = 0.8f)
        }
    }
}

@Composable
private fun A2uiSkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.7f,
    height: Dp = 14.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
    )
}

@Composable
private fun A2uiSkeletonImage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun A2uiComponent.textStyle(): TextStyle = when (raw.stringValue("variant")) {
    "h1" -> MaterialTheme.typography.displaySmall
    "h2" -> MaterialTheme.typography.headlineLarge
    "h3" -> MaterialTheme.typography.headlineMedium
    "h4" -> MaterialTheme.typography.headlineSmall
    "h5" -> MaterialTheme.typography.titleLarge
    "h6" -> MaterialTheme.typography.titleMedium
    "label" -> MaterialTheme.typography.labelLarge
    "caption" -> MaterialTheme.typography.bodySmall
    else -> MaterialTheme.typography.bodyMedium
}

@Composable
private fun A2uiComponent.resolveText(surface: A2uiSurfaceState): String? {
    val binding = raw["text"] ?: raw["content"] ?: raw["value"]
    return resolveBindingText(binding, surface)
}

@Composable
private fun A2uiComponent.resolveButtonLabel(surface: A2uiSurfaceState): String? {
    raw.stringValue("labelComponentId", "labelId")?.let { labelId ->
        return surface.components[labelId]?.resolveText(surface)
    }
    val label = raw["label"]
    if (label is JsonPrimitive) {
        surface.components[label.contentOrNull]?.resolveText(surface)?.let { return it }
    }
    return resolveBindingText(label ?: raw["text"], surface)
}

@Composable
private fun resolveBindingText(binding: JsonElement?, surface: A2uiSurfaceState): String? =
    when {
        binding is JsonObject && binding.stringValue("path") != null -> {
            val value by surface.dataModel.observe(binding.stringValue("path").orEmpty())
            value?.let(A2uiBindingResolver::displayText)
        }
        else -> when (val resolved = A2uiBindingResolver.resolve(binding, surface.dataModel)) {
            A2uiResolvedBinding.Missing -> null
            is A2uiResolvedBinding.Value -> A2uiBindingResolver.displayText(resolved.value)
        }
    }

@Composable
private fun A2uiComponent.resolveInputValue(
    surface: A2uiSurfaceState,
    binding: JsonElement?,
): String {
    val path = binding.bindingPath()
    return if (path != null) {
        val value by surface.dataModel.observe(path)
        value?.let(A2uiBindingResolver::displayText).orEmpty()
    } else {
        resolveBindingText(binding, surface).orEmpty()
    }
}

private fun A2uiComponent.action(surface: A2uiSurfaceState): A2uiAction? {
    val action = (raw["action"] ?: raw["onClick"]) as? JsonObject ?: return null
    val name = action.stringValue("name", "actionName", "type", "action") ?: return null
    val context = resolveA2uiActionContext(action["context"] ?: action["data"], surface.dataModel)
    val raw = buildJsonObject {
        action.forEach { (key, value) -> put(key, value) }
        put("actionName", name)
        put("name", name)
        put("surfaceId", surface.surfaceId)
        put("context", context)
    }
    return A2uiAction(
        name = name,
        surfaceId = surface.surfaceId,
        context = context,
        raw = raw,
    )
}

private fun A2uiComponent.spacing(): Dp =
    raw.dpValue("spacing") ?: when (raw.stringValue("spacing")) {
        "none" -> 0.dp
        "xs" -> 4.dp
        "sm" -> 8.dp
        "md" -> 12.dp
        "lg" -> 16.dp
        "xl" -> 24.dp
        else -> 8.dp
    }

private fun A2uiComponent.horizontalArrangement(): Arrangement.Horizontal =
    when (raw.stringValue("justify", "distribution")) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spaceBetween" -> Arrangement.SpaceBetween
        "spaceAround" -> Arrangement.SpaceAround
        "spaceEvenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing())
    }

private fun A2uiComponent.verticalAlignment(): Alignment.Vertical =
    when (raw.stringValue("align", "alignment")) {
        "top", "start" -> Alignment.Top
        "bottom", "end" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }

private fun A2uiComponent.weightRowChildren(): Boolean =
    raw.booleanValue("equalWidth") ?: (raw.stringValue("justify", "distribution") !in setOf(
        "spaceBetween",
        "spaceAround",
        "spaceEvenly",
    ))

private fun A2uiComponent.cornerRadius(): Dp =
    raw.dpValue("cornerRadius", "corner_radius") ?: 12.dp

private fun A2uiComponent.elevation(): Dp =
    raw.dpValue("elevation") ?: 1.dp

private fun A2uiComponent.height(): Dp? =
    raw.dpValue("height")

private fun A2uiComponent.aspectRatioModifier(): Modifier =
    raw.stringValue("aspectRatio")
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }
        ?.let { Modifier.aspectRatio(it) }
        ?: Modifier

private fun A2uiComponent.contentScale(): ContentScale =
    when (raw.stringValue("fit", "contentScale")) {
        "contain", "fit" -> ContentScale.Fit
        "fill", "stretch" -> ContentScale.FillBounds
        "none" -> ContentScale.None
        else -> ContentScale.Crop
    }

private fun A2uiComponent.inputValue(
    value: String,
    fieldType: String,
): JsonPrimitive =
    if (fieldType == "number") {
        value.toLongOrNull()?.let(::JsonPrimitive)
            ?: value.toDoubleOrNull()?.let(::JsonPrimitive)
            ?: JsonPrimitive(value)
    } else {
        JsonPrimitive(value)
    }

private fun A2uiComponent.dateTimePlaceholder(
    enableDate: Boolean,
    enableTime: Boolean,
): String =
    when {
        enableDate && enableTime -> "YYYY-MM-DD HH:mm"
        enableDate -> "YYYY-MM-DD"
        else -> "HH:mm"
    }

private fun A2uiComponent.toolApprovalProps(): ToolApprovalProps? {
    val toolName = raw.stringValue("toolName", "name")?.takeIf { it.isNotBlank() } ?: return null
    val callId = raw.stringValue("callId")?.takeIf { it.isNotBlank() } ?: return null
    val arguments = (raw["arguments"] as? JsonArray)
        ?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val key = obj.stringValue("key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ToolApprovalArgument(
                key = key,
                value = obj.stringValue("value").orEmpty(),
                isSensitive = obj.booleanValue("isSensitive") ?: false,
            )
        }
        .orEmpty()
    val affordances = (raw["affordances"] as? JsonArray)
        ?.mapNotNull { element -> ToolApprovalAffordance.from(element.jsonPrimitiveOrNull?.contentOrNull) }
        ?.takeIf { it.isNotEmpty() }
        ?: ToolApprovalAffordance.Defaults

    return ToolApprovalProps(
        toolName = toolName,
        toolDescription = raw.stringValue("toolDescription", "description"),
        arguments = arguments,
        risk = ToolApprovalRisk.from(raw.stringValue("riskLevel")),
        rationale = raw.stringValue("rationale"),
        affordances = affordances,
        timeoutSeconds = (raw.intValue("timeoutSeconds") ?: DefaultToolApprovalTimeoutSeconds).coerceAtLeast(0),
        callId = callId,
    )
}

private fun toolApprovalAction(
    surfaceId: String,
    callId: String,
    result: ToolApprovalResult,
): A2uiAction {
    val context = buildJsonObject {
        put("callId", callId)
        put("decision", result.decision)
        put("scope", result.scope)
    }
    val raw = buildJsonObject {
        put("actionName", ToolApprovalResponseAction)
        put("name", ToolApprovalResponseAction)
        put("surfaceId", surfaceId)
        put("context", context)
    }
    return A2uiAction(
        name = ToolApprovalResponseAction,
        surfaceId = surfaceId,
        context = context,
        raw = raw,
    )
}

private data class ToolApprovalProps(
    val toolName: String,
    val toolDescription: String?,
    val arguments: List<ToolApprovalArgument>,
    val risk: ToolApprovalRisk,
    val rationale: String?,
    val affordances: List<ToolApprovalAffordance>,
    val timeoutSeconds: Int,
    val callId: String,
)

private data class ToolApprovalArgument(
    val key: String,
    val value: String,
    val isSensitive: Boolean,
)

private data class ToolApprovalResult(
    val decision: String,
    val scope: String,
)

private enum class ToolApprovalRisk(
    val wireValue: String,
    val label: String,
) {
    Low("low", "Low"),
    Medium("medium", "Medium"),
    High("high", "High"),
    Destructive("destructive", "Destructive");

    companion object {
        fun from(value: String?): ToolApprovalRisk =
            entries.firstOrNull { it.wireValue == value } ?: Medium
    }
}

private enum class ToolApprovalAffordance(
    val wireValue: String,
    val label: String,
    val decision: String,
    val scope: String,
) {
    Once("once", "Once", "approve", "once"),
    Session("session", "This chat", "approve", "session"),
    Forever("forever", "Always", "approve", "forever"),
    Deny("deny", "Deny", "deny", "deny");

    companion object {
        val Defaults = listOf(Once, Session, Forever, Deny)

        fun from(value: String?): ToolApprovalAffordance? =
            entries.firstOrNull { it.wireValue == value }
    }
}

private data class ToolApprovalRiskStyle(
    val borderColor: Color,
    val borderWidth: Dp,
    val pillContainer: Color,
    val pillContent: Color,
)

@Composable
private fun ToolApprovalRisk.style(): ToolApprovalRiskStyle =
    when (this) {
        ToolApprovalRisk.Low -> ToolApprovalRiskStyle(
            borderColor = MaterialTheme.colorScheme.secondary,
            borderWidth = 1.dp,
            pillContainer = MaterialTheme.colorScheme.secondaryContainer,
            pillContent = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        ToolApprovalRisk.Medium -> ToolApprovalRiskStyle(
            borderColor = MaterialTheme.colorScheme.tertiary,
            borderWidth = 1.dp,
            pillContainer = MaterialTheme.colorScheme.tertiaryContainer,
            pillContent = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ToolApprovalRisk.High -> ToolApprovalRiskStyle(
            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.72f),
            borderWidth = 1.dp,
            pillContainer = MaterialTheme.colorScheme.errorContainer,
            pillContent = MaterialTheme.colorScheme.onErrorContainer,
        )
        ToolApprovalRisk.Destructive -> ToolApprovalRiskStyle(
            borderColor = MaterialTheme.colorScheme.error,
            borderWidth = 2.dp,
            pillContainer = MaterialTheme.colorScheme.error,
            pillContent = MaterialTheme.colorScheme.onError,
        )
    }

private fun ToolApprovalResult.statusLabel(): String =
    when {
        decision == "timeout" -> "Timed out"
        decision == "deny" -> "Denied"
        scope == "once" -> "Approved once"
        scope == "session" -> "Approved for this chat"
        scope == "forever" -> "Approved always"
        else -> "Resolved"
    }

private fun JsonObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitiveOrNull?.contentOrNull }

private fun JsonObject.intValue(vararg keys: String): Int? =
    stringValue(*keys)?.toIntOrNull()

private fun JsonObject.dpValue(vararg keys: String): Dp? =
    stringValue(*keys)?.toFloatOrNull()?.coerceIn(0f, 64f)?.dp

private fun JsonObject.booleanValue(vararg keys: String): Boolean? =
    stringValue(*keys)?.toBooleanStrictOrNull()

private fun JsonElement?.bindingPath(): String? =
    (this as? JsonObject)?.stringValue("path")

private fun String.matchesValidation(pattern: String): Boolean =
    runCatching { Regex(pattern).matches(this) }.getOrDefault(true)

private fun String.toDateMillis(): Long? =
    runCatching {
        LocalDate.parse(take(DateIsoLength), DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }.getOrNull()

private fun String.toLocalTime(): LocalTime? {
    val timeText = substringAfter('T', missingDelimiterValue = substringAfter(' ', missingDelimiterValue = this))
    return runCatching {
        LocalTime.parse(timeText.take(TimeIsoLength), DateTimeFormatter.ISO_LOCAL_TIME)
    }.getOrNull()
}

private fun formatDateTime(
    dateMillis: Long?,
    time: LocalTime?,
    enableDate: Boolean,
    enableTime: Boolean,
): String {
    val date = dateMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        ?: LocalDate.now(ZoneOffset.UTC)
    return when {
        enableDate && enableTime -> "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}T${time.orNow().format(TimeFormatter)}"
        enableDate -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        else -> time.orNow().format(TimeFormatter)
    }
}

private fun LocalTime?.orNow(): LocalTime =
    this ?: LocalTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)

private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

private const val DateIsoLength = 10
private const val TimeIsoLength = 5
private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val MaxRenderDepth = 32
private const val DefaultToolApprovalTimeoutSeconds = 30
private const val SensitiveMask = "********"
private const val ToolApprovalResponseAction = "tool_approval_response"
private const val ToolApprovalButtonsPerRow = 2
