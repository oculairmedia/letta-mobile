package com.letta.mobile.ui.a2ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
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
import com.letta.mobile.data.a2ui.A2UI_LIST_VIEW_WIDGET_ID
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiJsonPointer
import com.letta.mobile.data.a2ui.A2uiResolvedBinding
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.a2ui.LETTA_SCHEDULE_CARD_WIDGET_ID
import com.letta.mobile.data.a2ui.LETTA_SCHEDULE_SELECTOR_WIDGET_ID
import com.letta.mobile.data.a2ui.resolveA2uiActionContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
    actionResolutionToken: Int = 0,
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

    var pendingActionCount by remember(surface.surfaceId) { mutableStateOf(0) }
    val localStateScope = remember(surface.surfaceId) { A2uiLocalStateScope.root(surface.surfaceId) }
    val submitting = pendingActionCount > 0
    fun updatePendingActionCount(delta: Int) {
        pendingActionCount = (pendingActionCount + delta).coerceAtLeast(0)
    }

    CompositionLocalProvider(LocalA2uiLocalState provides localStateScope) {
        A2uiComponentNode(
            component = root,
            surface = surface,
            modifier = modifier,
            visited = emptySet(),
            onAction = onAction,
            surfaceSubmitting = submitting,
            onPendingActionDelta = ::updatePendingActionCount,
            actionResolutionToken = actionResolutionToken,
        )
    }
}

object A2uiTestTags {
    const val SurfaceMissing = "a2ui_surface_missing"
    const val SurfacePending = "a2ui_surface_pending"
    const val MissingComponent = "a2ui_missing_component"
    const val MissingText = "a2ui_missing_text"
    const val MissingImage = "a2ui_missing_image"
    const val TextField = "a2ui_text_field"
    const val DateTimeInput = "a2ui_date_time_input"
    const val Checkbox = "a2ui_checkbox"
    const val Switch = "a2ui_switch"
    const val Radio = "a2ui_radio"
    const val Slider = "a2ui_slider"
    const val Stepper = "a2ui_stepper"
    const val StepperDecrement = "a2ui_stepper_decrement"
    const val StepperIncrement = "a2ui_stepper_increment"
    const val Dropdown = "a2ui_dropdown"
    const val Chip = "a2ui_chip"
    const val FilterChip = "a2ui_filter_chip"
    const val Badge = "a2ui_badge"
    const val Tabs = "a2ui_tabs"
    const val Accordion = "a2ui_accordion"
    const val ScheduleCard = "a2ui_schedule_card"
    const val ScheduleSelectorInput = "a2ui_schedule_selector_input"
    const val Divider = "a2ui_divider"
    const val ToolApprovalCard = "a2ui_tool_approval_card"
    const val ToolApprovalSensitiveValue = "a2ui_tool_approval_sensitive_value"
    const val ToolApprovalCountdown = "a2ui_tool_approval_countdown"
    const val ButtonProgress = "a2ui_button_progress"
    const val LinearProgress = "a2ui_linear_progress"
    const val CircularProgress = "a2ui_circular_progress"
    const val ListView = "a2ui_list_view"
}

/**
 * Local-only state scope for A2UI widgets.
 *
 * Catalog authors may provide a component-level `localState` object, for example
 * `{ "localState": { "isExpanded": false, "tabIndex": 1 } }`, to seed UI
 * state that should stay on-device. Slots are keyed by `(surfaceId,
 * componentId, key)`: they survive recomposition and data-model updates, but are
 * intentionally forgotten when the surface leaves composition after deletion.
 */
@Stable
class A2uiLocalStateScope private constructor(
    val surfaceId: String,
    val componentId: String,
    private val booleanStates: MutableMap<A2uiLocalStateKey, MutableState<Boolean>>,
    private val intStates: MutableMap<A2uiLocalStateKey, MutableState<Int>>,
    private val defaults: JsonObject,
) {
    fun child(
        componentId: String,
        defaults: JsonObject = JsonObject(emptyMap()),
    ): A2uiLocalStateScope = A2uiLocalStateScope(
        surfaceId = surfaceId,
        componentId = componentId,
        booleanStates = booleanStates,
        intStates = intStates,
        defaults = defaults,
    )

    fun booleanState(key: String, initialValue: Boolean): MutableState<Boolean> =
        booleanStates.getOrPut(A2uiLocalStateKey(surfaceId, componentId, key)) {
            mutableStateOf(defaults[key]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() ?: initialValue)
        }

    fun intState(key: String, initialValue: Int): MutableState<Int> =
        intStates.getOrPut(A2uiLocalStateKey(surfaceId, componentId, key)) {
            mutableStateOf(defaults[key]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: initialValue)
        }

    companion object {
        fun root(surfaceId: String): A2uiLocalStateScope = A2uiLocalStateScope(
            surfaceId = surfaceId,
            componentId = A2uiLocalStateRootComponentId,
            booleanStates = mutableMapOf(),
            intStates = mutableMapOf(),
            defaults = JsonObject(emptyMap()),
        )
    }
}

val LocalA2uiLocalState = compositionLocalOf<A2uiLocalStateScope?> { null }

private data class A2uiLocalStateKey(
    val surfaceId: String,
    val componentId: String,
    val key: String,
)

@Composable
private fun A2uiComponentNode(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope = A2uiRenderScope.Root,
) {
    if (component.id in visited || visited.size > MaxRenderDepth) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }
    val parentLocalState = LocalA2uiLocalState.current
    val componentLocalState = remember(parentLocalState, component.id, component.raw["localState"]) {
        parentLocalState?.child(
            componentId = component.id,
            defaults = component.localStateDefaults(),
        )
    }
    if (componentLocalState != null) {
        CompositionLocalProvider(LocalA2uiLocalState provides componentLocalState) {
            A2uiComponentNodeContent(
                component = component,
                surface = surface,
                modifier = modifier,
                visited = visited,
                onAction = onAction,
                surfaceSubmitting = surfaceSubmitting,
                onPendingActionDelta = onPendingActionDelta,
                actionResolutionToken = actionResolutionToken,
                renderScope = renderScope,
            )
        }
    } else {
        A2uiComponentNodeContent(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = visited,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
    }
}

@Composable
private fun A2uiComponentNodeContent(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
) {
    val nextVisited = visited + component.id
    when (component.component) {
        "Text" -> A2uiText(component = component, surface = surface, modifier = modifier, renderScope = renderScope)
        "TextField" -> A2uiTextField(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "DateTimeInput" -> A2uiDateTimeInput(
            component = component,
            surface = surface,
            modifier = modifier,
            renderScope = renderScope,
        )
        "Checkbox" -> A2uiBooleanInput(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
            kind = A2uiBooleanInputKind.Checkbox,
        )
        "Switch" -> A2uiBooleanInput(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
            kind = A2uiBooleanInputKind.Switch,
        )
        "Radio" -> A2uiRadio(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "Slider" -> A2uiSlider(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "Stepper" -> A2uiStepper(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "LinearProgress" -> A2uiLinearProgress(
            component = component,
            surface = surface,
            modifier = modifier,
            renderScope = renderScope,
        )
        "CircularProgress" -> A2uiCircularProgress(
            component = component,
            surface = surface,
            modifier = modifier,
            renderScope = renderScope,
        )
        "Dropdown", "Select" -> A2uiDropdown(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "Chip" -> A2uiChip(
            component = component,
            surface = surface,
            modifier = modifier,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "FilterChip" -> A2uiFilterChip(
            component = component,
            surface = surface,
            modifier = modifier,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "Badge" -> A2uiBadge(
            component = component,
            surface = surface,
            modifier = modifier,
            renderScope = renderScope,
        )
        "Tabs" -> A2uiTabs(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
        "Accordion" -> A2uiAccordion(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
        LETTA_SCHEDULE_CARD_WIDGET_ID -> A2uiScheduleCard(
            component = component,
            surface = surface,
            modifier = modifier,
            onAction = onAction,
            renderScope = renderScope,
        )
        LETTA_SCHEDULE_SELECTOR_WIDGET_ID -> A2uiScheduleSelectorInput(
            component = component,
            surface = surface,
            modifier = modifier,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            renderScope = renderScope,
        )
        "Image" -> A2uiImage(component = component, surface = surface, modifier = modifier, renderScope = renderScope)
        "Divider" -> A2uiDivider(component = component, modifier = modifier)
        A2UI_LIST_VIEW_WIDGET_ID -> A2uiListView(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
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
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
        "Row" -> A2uiRow(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
        "Card" -> A2uiCard(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
        "Button" -> A2uiButton(
            component = component,
            surface = surface,
            modifier = modifier,
            onAction = onAction,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
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
    var argumentsExpanded by rememberA2uiLocalBooleanState("argumentsExpanded", true)
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
                onAction(toolApprovalAction(surface, props.callId, next))
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
            onAction(toolApprovalAction(surface, props.callId, timeout))
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
    renderScope: A2uiRenderScope,
) {
    val text = component.resolveText(surface, renderScope)
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
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["text"]
    val explicitPath = binding.bindingPath()?.let(renderScope::resolvePath)
    // letta-mobile-lwmo: when the agent omits an explicit value path, route
    // input through a synthetic data-model slot keyed by component id so
    // $componentId.value references in user_action contexts can resolve
    // the typed value. Previously this case wrote to Compose-local state
    // only (letta-mobile-ykkl), making the value invisible to action
    // emission. The synthetic path stays under a reserved /_inputs/
    // namespace to avoid colliding with agent-declared paths.
    val effectivePath = explicitPath ?: "/_inputs/${component.id}"
    val literalDefault = component.resolveInputValue(surface, binding, renderScope)
    val observedAtPath by surface.dataModel.observe(effectivePath)
    val label = resolveBindingText(component.raw["label"], surface, renderScope)
    val placeholder = resolveBindingText(component.raw["placeholder"], surface, renderScope)
    val fieldType = component.raw.stringValue("textFieldType", "variant", "type").orEmpty()
    val validation = component.raw.stringValue("validationRegexp")

    val value = if (explicitPath != null) {
        // Bound surfaces: existing behavior — read from the observed binding.
        literalDefault
    } else {
        // Unbound surfaces: synthetic data-model slot drives display; literal
        // binding (if any) is the initial default until the user types.
        observedAtPath?.let(A2uiBindingResolver::displayText) ?: literalDefault
    }
    val isError = validation != null && value.isNotBlank() && !value.matchesValidation(validation)

    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            surface.dataModel.applyPatch(
                path = effectivePath,
                value = component.inputValue(next, fieldType),
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(A2uiTestTags.TextField),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        isError = isError,
        readOnly = surfaceSubmitting,
        supportingText = when {
            surfaceSubmitting -> ({ Text("submitting...") })
            isError -> ({ Text("Invalid value") })
            else -> null
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
private fun A2uiBooleanInput(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
    kind: A2uiBooleanInputKind,
) {
    val binding = component.raw["value"] ?: component.raw["checked"] ?: component.raw["selected"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val boundChecked = component.resolveInputValue(surface, binding, renderScope).toBooleanStrictOrNull() ?: false
    val localChecked = rememberA2uiLocalBooleanState("value", boundChecked)
    val checked = if (path != null) boundChecked else localChecked.value
    val label = component.resolveControlLabel(surface, renderScope)

    fun update(next: Boolean) {
        if (path != null) {
            surface.dataModel.applyPatch(path = path, value = JsonPrimitive(next))
        } else {
            localChecked.value = next
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !surfaceSubmitting) { update(!checked) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (kind) {
            A2uiBooleanInputKind.Checkbox -> Checkbox(
                checked = checked,
                onCheckedChange = { update(it) },
                enabled = !surfaceSubmitting,
                modifier = Modifier.testTag(kind.testTag),
            )
            A2uiBooleanInputKind.Switch -> Switch(
                checked = checked,
                onCheckedChange = { update(it) },
                enabled = !surfaceSubmitting,
                modifier = Modifier.testTag(kind.testTag),
            )
        }
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (surfaceSubmitting) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun A2uiRadio(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["selected"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val boundValue = component.resolveInputValue(surface, binding, renderScope)
    var localValue by remember(component.id) { mutableStateOf(boundValue) }
    val value = if (path != null) boundValue else localValue
    val label = component.resolveControlLabel(surface, renderScope)
    val options = component.resolveRadioOptions(surface, renderScope)

    if (options.isEmpty()) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    fun update(next: String) {
        if (path != null) {
            surface.dataModel.applyPatch(path = path, value = JsonPrimitive(next))
        } else {
            localValue = next
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(A2uiTestTags.Radio),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !surfaceSubmitting) { update(option.key) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = value == option.key,
                    onClick = { update(option.key) },
                    enabled = !surfaceSubmitting,
                )
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (surfaceSubmitting) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun A2uiDateTimeInput(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["text"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val value = component.resolveInputValue(surface, binding, renderScope)
    val label = resolveBindingText(component.raw["label"], surface, renderScope) ?: "Date and time"
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
        // letta-mobile-ykkl: lift overlay above the OutlinedTextField's
        // internal Surface so taps reliably hit the picker-launch
        // clickable, not the (readOnly) field's focus handler.
        Box(
            modifier = Modifier
                .matchParentSize()
                .zIndex(1f)
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
    renderScope: A2uiRenderScope,
) {
    val imageUrl = resolveBindingText(component.raw["url"] ?: component.raw["src"], surface, renderScope)
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
            renderScope,
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
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
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
                    surfaceSubmitting = surfaceSubmitting,
                    onPendingActionDelta = onPendingActionDelta,
                    actionResolutionToken = actionResolutionToken,
                    renderScope = renderScope,
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
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
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
                    surfaceSubmitting = surfaceSubmitting,
                    onPendingActionDelta = onPendingActionDelta,
                    actionResolutionToken = actionResolutionToken,
                    renderScope = renderScope,
                )
            }
        }
    }
}

@Composable
private fun A2uiListView(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
) {
    val template = component.listTemplate
    if (template == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    val itemsPath = renderScope.resolvePath(template.itemsPath)
    val itemsValue by surface.dataModel.observe(itemsPath)
    val items = itemsValue as? JsonArray
    val templateComponent = surface.components[template.itemTemplateComponentId]
    if (items == null || templateComponent == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(A2uiTestTags.ListView),
        verticalArrangement = Arrangement.spacedBy(component.spacing()),
    ) {
        items.forEachIndexed { index, item ->
            val itemScope = A2uiRenderScope(basePath = itemsPath.appendJsonPointerSegment(index.toString()))
            val itemKey = item.resolveItemKey(template.itemKeyPath) ?: index.toString()
            key(component.id, itemKey, index) {
                A2uiComponentNode(
                    component = templateComponent,
                    surface = surface,
                    visited = visited,
                    onAction = onAction,
                    surfaceSubmitting = surfaceSubmitting,
                    onPendingActionDelta = onPendingActionDelta,
                    actionResolutionToken = actionResolutionToken,
                    renderScope = itemScope,
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
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
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
                        surfaceSubmitting = surfaceSubmitting,
                        onPendingActionDelta = onPendingActionDelta,
                        actionResolutionToken = actionResolutionToken,
                        renderScope = renderScope,
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
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
) {
    val label = component.resolveButtonLabel(surface, renderScope)
    val action = component.action(surface, renderScope)
    val haptic = LocalHapticFeedback.current
    var inFlight by remember(surface.surfaceId, component.id) { mutableStateOf(false) }

    LaunchedEffect(inFlight) {
        if (!inFlight) return@LaunchedEffect
        delay(A2uiButtonLocalTimeoutMillis)
        inFlight = false
        onPendingActionDelta(-1)
    }

    LaunchedEffect(actionResolutionToken) {
        if (actionResolutionToken > 0 && inFlight) {
            inFlight = false
            onPendingActionDelta(-1)
        }
    }

    Button(
        onClick = {
            if (inFlight) return@Button
            // letta-mobile-ykkl diagnostic: log the dispatch hop so the
            // chain "Compose onClick → onAction → WsChatBridge → wire"
            // is traceable in adb logcat without a debugger attached.
            val resolved = component.action(surface, renderScope)
            if (resolved == null) {
                android.util.Log.w(
                    "A2UI",
                    "Button onClick: action unresolved surfaceId=${surface.surfaceId} " +
                        "componentId=${component.id} raw=${component.raw["action"] ?: component.raw["onClick"]}",
                )
            } else {
                android.util.Log.i(
                    "A2UI",
                    "Button onClick: dispatching surfaceId=${surface.surfaceId} " +
                        "componentId=${component.id} event=${resolved.name}",
                )
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                inFlight = true
                onPendingActionDelta(1)
                onAction(resolved)
            }
        },
        enabled = label != null && action != null && !inFlight,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .testTag(A2uiTestTags.ButtonProgress),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = label,
                    color = if (inFlight) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    } else {
                        Color.Unspecified
                    },
                )
            }
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
private fun A2uiComponent.resolveText(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? {
    val binding = raw["text"] ?: raw["content"] ?: raw["value"]
    return resolveBindingText(binding, surface, renderScope)
}

@Composable
private fun A2uiComponent.resolveButtonLabel(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? {
    // letta-mobile-njzb: A2UI v0.9 Basic Catalog defines Button.child as
    // "the ID of the child component. Use a Text component for a labeled
    // button." Resolve `child` first — that's the canonical spec field
    // and what the shim emits. `labelComponentId` / `labelId` / `label`
    // (string-id) are pre-spec aliases we keep for back-compat with any
    // payload still using the older field names.
    raw.stringValue("child", "labelComponentId", "labelId")?.let { childId ->
        surface.components[childId]?.resolveText(surface, renderScope)?.let { return it }
    }
    val label = raw["label"]
    if (label is JsonPrimitive) {
        surface.components[label.contentOrNull]?.resolveText(surface, renderScope)?.let { return it }
    }
    return resolveBindingText(label ?: raw["text"], surface, renderScope)
}

@Composable
private fun A2uiComponent.resolveControlLabel(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    resolveBindingText(raw["label"] ?: raw["text"] ?: raw["title"], surface, renderScope)

@Composable
private fun A2uiComponent.resolveProgressLabel(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    resolveBindingText(raw["label"] ?: raw["text"] ?: raw["title"], surface, renderScope)

@Composable
private fun A2uiScheduleCard(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
    renderScope: A2uiRenderScope,
) {
    val props = component.scheduleCardProps(surface, renderScope)
    if (props == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Schedule ${props.name}, ${props.status.label}"
                props.nextRun?.let { stateDescription = "Next run $it" }
            }
            .testTag(A2uiTestTags.ScheduleCard),
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
private fun ScheduleStatusPill(status: ScheduleStatus) {
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
private fun ScheduleMetaLine(label: String, value: String) {
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
private fun A2uiScheduleSelectorInput(
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
            .testTag(A2uiTestTags.ScheduleSelectorInput),
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
private fun A2uiChip(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val label = component.resolveControlLabel(surface, renderScope)
    val action = component.action(surface, renderScope)
    if (label == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingText))
        return
    }

    AssistChip(
        onClick = { action?.let(onAction) },
        enabled = action != null && !surfaceSubmitting,
        label = { Text(label) },
        modifier = modifier.testTag(A2uiTestTags.Chip),
    )
}

@Composable
private fun A2uiFilterChip(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["checked"] ?: component.raw["selected"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val boundSelected = component.resolveInputValue(surface, binding, renderScope).toBooleanStrictOrNull() ?: false
    val localSelected = rememberA2uiLocalBooleanState("value", boundSelected)
    val selected = if (path != null) boundSelected else localSelected.value
    val label = component.resolveControlLabel(surface, renderScope)
    if (label == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingText))
        return
    }

    fun update(next: Boolean) {
        if (path != null) {
            surface.dataModel.applyPatch(path = path, value = JsonPrimitive(next))
        } else {
            localSelected.value = next
        }
    }

    FilterChip(
        selected = selected,
        onClick = { update(!selected) },
        enabled = !surfaceSubmitting,
        label = { Text(label) },
        modifier = modifier.testTag(A2uiTestTags.FilterChip),
    )
}

@Composable
private fun A2uiBadge(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    renderScope: A2uiRenderScope,
) {
    val text = resolveBindingText(
        component.raw["count"] ?: component.raw["text"] ?: component.raw["label"] ?: component.raw["value"],
        surface,
        renderScope,
    )
    if (text == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingText))
        return
    }

    Badge(modifier = modifier.testTag(A2uiTestTags.Badge)) {
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun A2uiTabs(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
) {
    val items = component.resolveTabs(surface, renderScope)
    if (items.isEmpty()) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }
    val defaultIndex = component.defaultTabIndex(items)
    val selectedIndexState = rememberA2uiLocalIntState("selectedTabIndex", defaultIndex)
    val selectedIndex = selectedIndexState.value.coerceIn(0, items.lastIndex)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(A2uiTestTags.Tabs),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrimaryTabRow(selectedTabIndex = selectedIndex) {
            items.forEachIndexed { index, item ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { selectedIndexState.value = index },
                    text = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        Crossfade(targetState = selectedIndex, label = "a2ui-tab-content") { index ->
            val child = surface.components[items[index].childId]
            if (child == null) {
                A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
            } else {
                A2uiComponentNode(
                    component = child,
                    surface = surface,
                    visited = visited,
                    onAction = onAction,
                    surfaceSubmitting = surfaceSubmitting,
                    onPendingActionDelta = onPendingActionDelta,
                    actionResolutionToken = actionResolutionToken,
                    renderScope = renderScope,
                )
            }
        }
    }
}

@Composable
private fun A2uiAccordion(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
) {
    val items = component.resolveAccordionItems(surface, renderScope)
    if (items.isEmpty()) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag(A2uiTestTags.Accordion),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val expandedState = rememberA2uiLocalBooleanState("expanded_${item.key}", item.defaultOpen)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(component.cornerRadius()),
                tonalElevation = component.elevation(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedState.value = !expandedState.value }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (expandedState.value) "−" else "+",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(visible = expandedState.value) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            val child = surface.components[item.childId]
                            if (child == null) {
                                A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
                            } else {
                                A2uiComponentNode(
                                    component = child,
                                    surface = surface,
                                    visited = visited,
                                    onAction = onAction,
                                    surfaceSubmitting = surfaceSubmitting,
                                    onPendingActionDelta = onPendingActionDelta,
                                    actionResolutionToken = actionResolutionToken,
                                    renderScope = renderScope,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun A2uiDropdown(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["selected"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val boundValue = component.resolveInputValue(surface, binding, renderScope)
    var localValue by remember(component.id) { mutableStateOf(boundValue) }
    val value = if (path != null) boundValue else localValue
    val label = component.resolveControlLabel(surface, renderScope)
    val placeholder = resolveBindingText(component.raw["placeholder"], surface, renderScope)
    val validation = component.raw.stringValue("validationRegexp")
    val options = component.resolveRadioOptions(surface, renderScope)
    val selectedLabel = options.firstOrNull { it.key == value }?.label.orEmpty()
    val isError = validation != null && value.isNotBlank() && !value.matchesValidation(validation)
    var expanded by remember(component.id) { mutableStateOf(false) }

    fun update(next: String) {
        if (path != null) {
            surface.dataModel.applyPatch(path = path, value = JsonPrimitive(next))
        } else {
            localValue = next
        }
        expanded = false
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!surfaceSubmitting) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .testTag(A2uiTestTags.Dropdown),
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            readOnly = true,
            enabled = !surfaceSubmitting,
            singleLine = true,
            isError = isError,
            supportingText = when {
                surfaceSubmitting -> ({ Text("submitting...") })
                isError -> ({ Text("Invalid value") })
                else -> null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No options", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            } else {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { update(option.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun A2uiSlider(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val range = component.numericRange()
    val boundValue = component.resolveNumericInputValue(surface, binding, renderScope, range.min)
    var localValue by remember(component.id) { mutableStateOf(boundValue) }
    val value = if (path != null) boundValue else localValue
    val label = component.resolveControlLabel(surface, renderScope)

    fun update(next: Double) {
        val stepped = range.coerce(next)
        if (path != null) {
            surface.dataModel.applyPatch(path = path, value = stepped.numericJsonPrimitive(range.integralStep))
        } else {
            localValue = stepped
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            label?.let {
                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value.numericDisplay(range.integralStep),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = range.coerce(value).toFloat(),
            onValueChange = { update(it.toDouble()) },
            modifier = Modifier.testTag(A2uiTestTags.Slider),
            enabled = !surfaceSubmitting,
            valueRange = range.min.toFloat()..range.max.toFloat(),
            steps = range.sliderSteps,
        )
    }
}

@Composable
private fun A2uiStepper(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val range = component.numericRange()
    val boundValue = component.resolveNumericInputValue(surface, binding, renderScope, range.min)
    var localValue by remember(component.id) { mutableStateOf(boundValue) }
    val value = if (path != null) boundValue else localValue
    val label = component.resolveControlLabel(surface, renderScope)

    fun update(next: Double) {
        val stepped = range.coerce(next)
        if (path != null) {
            surface.dataModel.applyPatch(path = path, value = stepped.numericJsonPrimitive(range.integralStep))
        } else {
            localValue = stepped
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(A2uiTestTags.Stepper),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label?.let {
            Text(
                text = it,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (surfaceSubmitting) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        OutlinedButton(
            onClick = { update(value - range.step) },
            enabled = !surfaceSubmitting && value > range.min,
            modifier = Modifier.testTag(A2uiTestTags.StepperDecrement),
        ) {
            Text("−")
        }
        Text(
            text = value.numericDisplay(range.integralStep),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(
            onClick = { update(value + range.step) },
            enabled = !surfaceSubmitting && value < range.max,
            modifier = Modifier.testTag(A2uiTestTags.StepperIncrement),
        ) {
            Text("+")
        }
    }
}


@Composable
private fun A2uiLinearProgress(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["progress"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val progressValue by surface.dataModel.observe(path.orEmpty())
    val progress = progressValue.progressFractionOrNull()
    val label = component.resolveProgressLabel(surface, renderScope)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        label?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress?.let { value ->
                    Text(
                        text = value.progressPercentLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (path != null && progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Linear progress ${progress.progressPercentLabel()}"
                        stateDescription = progress.progressPercentLabel()
                    }
                    .testTag(A2uiTestTags.LinearProgress),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Linear progress indeterminate"
                        stateDescription = "Indeterminate"
                    }
                    .testTag(A2uiTestTags.LinearProgress),
            )
        }
    }
}

@Composable
private fun A2uiCircularProgress(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["progress"]
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    val progressValue by surface.dataModel.observe(path.orEmpty())
    val progress = progressValue.progressFractionOrNull()
    val label = component.resolveProgressLabel(surface, renderScope)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (path != null && progress != null) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription = "Circular progress ${progress.progressPercentLabel()}"
                            stateDescription = progress.progressPercentLabel()
                        }
                        .testTag(A2uiTestTags.CircularProgress),
                )
                Text(
                    text = progress.progressPercentLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier
                    .semantics {
                        contentDescription = "Circular progress indeterminate"
                        stateDescription = "Indeterminate"
                    }
                    .testTag(A2uiTestTags.CircularProgress),
            )
        }
    }
}

@Composable
private fun A2uiComponent.resolveRadioOptions(
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
): List<A2uiRadioOption> {
    val staticOptions = raw["options"] as? JsonArray
    if (staticOptions != null) {
        return staticOptions.mapIndexedNotNull { index, option ->
            radioOptionFromElement(option, index, surface, renderScope)
        }
    }

    val itemsPath = (raw["items"] ?: raw["options"]).bindingPath()?.let(renderScope::resolvePath) ?: return emptyList()
    val itemsValue by surface.dataModel.observe(itemsPath)
    val items = itemsValue as? JsonArray ?: return emptyList()
    return items.mapIndexedNotNull { index, item ->
        radioOptionFromElement(
            element = item,
            index = index,
            surface = surface,
            renderScope = A2uiRenderScope(basePath = itemsPath.appendJsonPointerSegment(index.toString())),
        )
    }
}

@Composable
private fun A2uiComponent.resolveTabs(
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
): List<A2uiTabItem> {
    val itemElements = raw["items"] as? JsonArray ?: raw["tabs"] as? JsonArray ?: return emptyList()
    return itemElements.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val key = item.stringValue("key", "id", "value") ?: index.toString()
        val label = resolveBindingText(
            item["label"] ?: item["title"] ?: item["text"] ?: JsonPrimitive(key),
            surface,
            renderScope,
        ) ?: key
        val childId = item.stringValue("child", "content", "component", "childId")
            ?: raw.stringValue("childTemplate", "childTemplateId", "itemTemplate")
            ?: return@mapIndexedNotNull null
        A2uiTabItem(key = key, label = label, childId = childId)
    }
}

private fun A2uiComponent.defaultTabIndex(items: List<A2uiTabItem>): Int {
    val default = raw.stringValue("default", "defaultKey", "selected", "value") ?: return 0
    return default.toIntOrNull()?.coerceIn(0, items.lastIndex)
        ?: items.indexOfFirst { it.key == default }.takeIf { it >= 0 }
        ?: 0
}

@Composable
private fun A2uiComponent.resolveAccordionItems(
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
): List<A2uiAccordionItem> {
    val itemElements = raw["items"] as? JsonArray ?: return emptyList()
    return itemElements.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val key = item.stringValue("key", "id", "value") ?: index.toString()
        val title = resolveBindingText(
            item["title"] ?: item["label"] ?: item["text"] ?: JsonPrimitive(key),
            surface,
            renderScope,
        ) ?: key
        val childId = item.stringValue("child", "content", "component", "childId") ?: return@mapIndexedNotNull null
        A2uiAccordionItem(
            key = key,
            title = title,
            childId = childId,
            defaultOpen = item.booleanValue("defaultOpen", "open", "expanded") ?: false,
        )
    }
}

@Composable
private fun A2uiComponent.radioOptionFromElement(
    element: JsonElement,
    index: Int,
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
): A2uiRadioOption? {
    val option = element as? JsonObject
    if (option == null) {
        val text = A2uiBindingResolver.displayText(element).takeIf { it.isNotBlank() } ?: return null
        return A2uiRadioOption(key = text, label = text)
    }

    val keyField = raw.stringValue("optionKey", "itemKey", "keyPath") ?: "key"
    val labelField = raw.stringValue("optionLabel", "itemLabel", "labelPath") ?: "label"
    val key = option.stringValue(keyField, "key", "value", "id")
        ?: element.resolveItemKey(keyField)
        ?: index.toString()
    val labelBinding = option[labelField]
        ?: option["label"]
        ?: option["text"]
        ?: option["title"]
        ?: option["name"]
    val label = resolveBindingText(labelBinding ?: JsonPrimitive(key), surface, renderScope) ?: key
    return A2uiRadioOption(key = key, label = label)
}

@Composable
private fun resolveBindingText(
    binding: JsonElement?,
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
): String? =
    when {
        binding is JsonObject && binding.stringValue("path") != null -> {
            val value by surface.dataModel.observe(renderScope.resolvePath(binding.stringValue("path").orEmpty()))
            value?.let(A2uiBindingResolver::displayText)
        }
        else -> when (val resolved = A2uiBindingResolver.resolve(binding?.withScopedPaths(renderScope), surface.dataModel)) {
            A2uiResolvedBinding.Missing -> null
            is A2uiResolvedBinding.Value -> A2uiBindingResolver.displayText(resolved.value)
        }
    }

@Composable
private fun A2uiComponent.resolveInputValue(
    surface: A2uiSurfaceState,
    binding: JsonElement?,
    renderScope: A2uiRenderScope,
): String {
    val path = binding.bindingPath()?.let(renderScope::resolvePath)
    return if (path != null) {
        val value by surface.dataModel.observe(path)
        value?.let(A2uiBindingResolver::displayText).orEmpty()
    } else {
        resolveBindingText(binding, surface, renderScope).orEmpty()
    }
}

private fun A2uiComponent.action(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): A2uiAction? {
    val action = (raw["action"] ?: raw["onClick"]) as? JsonObject ?: return null
    // letta-mobile-ykkl: A2UI v0.9 Basic Catalog declares Button.action
    // as `Action` whose payload is nested under `event` (Action.event).
    // The pre-spec shape was `action: { name: … }` which we still accept
    // as an alias. Look both at the action root and under `event`
    // before giving up so payloads from either generation are clickable.
    val eventBlock = (action["event"] as? JsonObject)
    val name = action.stringValue("name", "actionName", "type", "action")
        ?: eventBlock?.stringValue("name", "actionName", "type", "action")
        ?: return null
    val actionId = action.stringValue("actionId", "action_id", "id")
        ?: eventBlock?.stringValue("actionId", "action_id", "id")
    val contextSource = (eventBlock?.get("context") ?: eventBlock?.get("data"))
        ?: action["context"]
        ?: action["data"]
    val resolvedContext = resolveA2uiActionContext(contextSource?.withScopedPaths(renderScope), surface)
    // letta-mobile-lwmo: form-style surfaces (TextField + Button) lost the
    // typed value when the agent emitted Button.action with no context
    // bindings. Two paths to surface the data model to the agent:
    //   (a) Spec: agent sets createSurface.sendDataModel:true → always attach.
    //   (b) Safety net: agent's declared context is empty but the surface
    //       has data → attach anyway so the agent gets the inputs even
    //       before the shim prompt change ships.
    val dataModelRoot = surface.dataModel.root
    val attachDataModel = dataModelRoot is JsonObject && dataModelRoot.isNotEmpty() &&
        (surface.sendDataModel || resolvedContext.isEmpty())
    val context = if (attachDataModel) {
        JsonObject(resolvedContext + ("data_model" to dataModelRoot))
    } else {
        resolvedContext
    }
    val raw = buildJsonObject {
        action.forEach { (key, value) -> put(key, value) }
        put("actionName", name)
        put("name", name)
        put("surfaceId", surface.surfaceId)
        put("context", context)
        surface.runId?.let { put("runId", it) }
        surface.turnId?.let { put("turnId", it) }
        actionId?.let { put("actionId", it) }
    }
    return A2uiAction(
        name = name,
        surfaceId = surface.surfaceId,
        context = context,
        runId = surface.runId,
        turnId = surface.turnId,
        actionId = actionId,
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

@Composable
private fun A2uiComponent.resolveNumericInputValue(
    surface: A2uiSurfaceState,
    binding: JsonElement?,
    renderScope: A2uiRenderScope,
    defaultValue: Double,
): Double = resolveInputValue(surface, binding, renderScope).toDoubleOrNull() ?: defaultValue

private fun A2uiComponent.numericRange(): A2uiNumericRange {
    val min = raw.doubleValue("min", "minimum") ?: 0.0
    val rawMax = raw.doubleValue("max", "maximum") ?: 100.0
    val max = rawMax.takeIf { it > min } ?: (min + 1.0)
    val rawStep = raw.doubleValue("step") ?: 1.0
    val step = rawStep.takeIf { it > 0.0 } ?: 1.0
    return A2uiNumericRange(min = min, max = max, step = step)
}

private fun A2uiNumericRange.coerce(value: Double): Double {
    val constrained = value.coerceIn(min, max)
    val snapped = min + (((constrained - min) / step).roundToInt() * step)
    return snapped.coerceIn(min, max)
}

private fun Double.numericJsonPrimitive(integral: Boolean): JsonPrimitive =
    if (integral) JsonPrimitive(roundToInt().toLong()) else JsonPrimitive(this)

private fun Double.numericDisplay(integral: Boolean): String =
    if (integral) roundToInt().toString() else toString().trimEnd('0').trimEnd('.')


private fun JsonElement?.progressFractionOrNull(): Float? =
    this?.let(A2uiBindingResolver::displayText)?.toFloatOrNull()?.coerceIn(0f, 1f)

private fun Float.progressPercentLabel(): String = "${(this * 100f).roundToInt()}%"

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

@Composable
private fun A2uiComponent.scheduleCardProps(
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
private fun scheduleSelectorValueAt(
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

private fun A2uiComponent.scheduleSelectorId(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    A2uiBindingResolver.resolve((raw["id"] ?: raw["scheduleId"])?.withScopedPaths(renderScope), surface.dataModel)
        .let { it as? A2uiResolvedBinding.Value }
        ?.value
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }

private fun A2uiComponent.scheduleSelectorAgentId(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    A2uiBindingResolver.resolve(raw["agentId"]?.withScopedPaths(renderScope), surface.dataModel)
        .let { it as? A2uiResolvedBinding.Value }
        ?.value
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }

private fun A2uiComponent.scheduleSelectorMessage(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String =
    A2uiBindingResolver.resolve((raw["message"] ?: raw["content"])?.withScopedPaths(renderScope), surface.dataModel)
        .let { it as? A2uiResolvedBinding.Value }
        ?.value
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }
        ?: ""

private fun scheduleSummary(
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

private fun scheduleSelectorJson(mode: ScheduleSelectorMode, value: String): JsonObject =
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

private fun JsonObject.toScheduleDefinitionJson(): JsonObject {
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

private fun scheduleIdAction(
    surface: A2uiSurfaceState,
    name: String,
    id: String,
): A2uiAction {
    val context = buildJsonObject { put("id", id) }
    return scheduleAction(surface = surface, name = name, actionId = id, context = context)
}

private fun scheduleSaveAction(
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

private fun scheduleAction(
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
        surface.runId?.let { put("runId", it) }
        surface.turnId?.let { put("turnId", it) }
        actionId?.let { put("actionId", it) }
    }
    return A2uiAction(
        name = name,
        surfaceId = surface.surfaceId,
        context = context,
        runId = surface.runId,
        turnId = surface.turnId,
        actionId = actionId,
        raw = raw,
    )
}

private fun toolApprovalAction(
    surface: A2uiSurfaceState,
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
        put("surfaceId", surface.surfaceId)
        put("context", context)
        surface.runId?.let { put("runId", it) }
        surface.turnId?.let { put("turnId", it) }
        put("actionId", callId)
    }
    return A2uiAction(
        name = ToolApprovalResponseAction,
        surfaceId = surface.surfaceId,
        context = context,
        runId = surface.runId,
        turnId = surface.turnId,
        actionId = callId,
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

private data class ScheduleCardProps(
    val id: String,
    val name: String,
    val agentName: String?,
    val status: ScheduleStatus,
    val summary: String?,
    val scheduleText: String,
    val nextRun: String?,
    val lastRun: String?,
    val paused: Boolean,
)

private data class ScheduleSelectorValue(
    val mode: ScheduleSelectorMode,
    val value: String,
)

private enum class ScheduleStatus(
    val wireValues: Set<String>,
    val label: String,
) {
    Active(setOf("active", "running", "live", "scheduled"), "Active"),
    Paused(setOf("paused", "disabled"), "Paused"),
    Failed(setOf("failed", "error"), "Failed"),
    Idle(setOf("idle", "pending", ""), "Idle");

    companion object {
        fun from(value: String): ScheduleStatus =
            entries.firstOrNull { status -> value.lowercase() in status.wireValues } ?: Active
    }
}

private enum class ScheduleSelectorMode(
    val wireValue: String,
    val label: String,
    val scheduleType: String,
    val valueLabel: String,
    val placeholder: String,
) {
    Cron("cron", "Cron", "recurring", "Cron expression", "0 9 * * 1"),
    Every("every", "Every", "recurring", "Every N minutes", "15"),
    At("at", "At", "one-time", "Unix timestamp", "1790000000"),
    ;

    fun preview(value: String): String = when (this) {
        Cron -> if (value.isBlank()) "Runs on a cron expression" else "Runs on cron: $value"
        Every -> if (value.isBlank()) "Runs every N minutes" else "Runs every $value minutes"
        At -> if (value.isBlank()) "Runs once at a Unix timestamp" else "Runs once at $value"
    }

    fun defaultValue(current: String): String = current.ifBlank {
        when (this) {
            Cron -> "0 9 * * 1"
            Every -> "15"
            At -> ""
        }
    }

    companion object {
        fun from(value: String?): ScheduleSelectorMode = when (value?.lowercase()) {
            "every" -> Every
            "at", "one-time", "once" -> At
            else -> Cron
        }
    }
}

private enum class A2uiBooleanInputKind(val testTag: String) {
    Checkbox(A2uiTestTags.Checkbox),
    Switch(A2uiTestTags.Switch),
}

private data class A2uiRadioOption(
    val key: String,
    val label: String,
)

private data class A2uiTabItem(
    val key: String,
    val label: String,
    val childId: String,
)

private data class A2uiAccordionItem(
    val key: String,
    val title: String,
    val childId: String,
    val defaultOpen: Boolean,
)

private data class A2uiNumericRange(
    val min: Double,
    val max: Double,
    val step: Double,
) {
    val integralStep: Boolean = step % 1.0 == 0.0
    val sliderSteps: Int = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)
}

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

private fun JsonObject.doubleValue(vararg keys: String): Double? =
    stringValue(*keys)?.toDoubleOrNull()

private fun JsonObject.booleanValue(vararg keys: String): Boolean? =
    stringValue(*keys)?.toBooleanStrictOrNull()

private fun JsonElement?.bindingPath(): String? =
    (this as? JsonObject)?.stringValue("path")

@Composable
private fun rememberA2uiLocalIntState(
    key: String,
    initialValue: Int,
): MutableState<Int> {
    val scope = LocalA2uiLocalState.current
    return scope?.intState(key, initialValue) ?: remember(key) { mutableStateOf(initialValue) }
}

@Composable
private fun rememberA2uiLocalBooleanState(
    key: String,
    initialValue: Boolean,
): MutableState<Boolean> {
    val scope = LocalA2uiLocalState.current
    return scope?.booleanState(key, initialValue) ?: remember(key) { mutableStateOf(initialValue) }
}

private fun A2uiComponent.localStateDefaults(): JsonObject =
    raw["localState"] as? JsonObject ?: JsonObject(emptyMap())

private data class A2uiRenderScope(
    val basePath: String? = null,
) {
    fun resolvePath(path: String): String {
        val normalized = A2uiJsonPointer.normalize(path)
        val base = basePath ?: return normalized
        return if (path.trim().startsWith("/")) {
            normalized
        } else {
            base.appendJsonPointerSegment(path)
        }
    }

    companion object {
        val Root = A2uiRenderScope()
    }
}

private fun JsonElement.withScopedPaths(renderScope: A2uiRenderScope): JsonElement =
    when (this) {
        is JsonArray -> JsonArray(map { it.withScopedPaths(renderScope) })
        is JsonObject -> JsonObject(
            mapValues { (key, value) ->
                if (key == "path" && value is JsonPrimitive) {
                    JsonPrimitive(renderScope.resolvePath(value.contentOrNull.orEmpty()))
                } else {
                    value.withScopedPaths(renderScope)
                }
            }
        )
        else -> this
    }

private fun String.appendJsonPointerSegment(segment: String): String =
    A2uiJsonPointer.normalize(this).trimEnd('/') + "/" + segment.escapeJsonPointerSegment()

private fun String.escapeJsonPointerSegment(): String =
    replace("~", "~0").replace("/", "~1")

private fun JsonElement.resolveItemKey(path: String): String? =
    A2uiJsonPointer.resolve(this, path)
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }

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
private const val A2uiButtonLocalTimeoutMillis = 10_000L
private const val A2uiLocalStateRootComponentId = "__surface__"
private const val SensitiveMask = "********"
private const val ToolApprovalResponseAction = "tool_approval_response"
private const val ToolApprovalButtonsPerRow = 2
private const val ScheduleSaveAction = "schedule.save"
private const val ScheduleDeleteAction = "schedule.delete"
private const val SchedulePauseAction = "schedule.pause"
private const val ScheduleResumeAction = "schedule.resume"
private const val ScheduleRunNowAction = "schedule.run_now"
