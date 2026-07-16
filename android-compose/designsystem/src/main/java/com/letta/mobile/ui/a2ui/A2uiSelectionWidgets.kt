package com.letta.mobile.ui.a2ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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
import com.letta.mobile.ui.haptics.HapticEffects
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
internal fun A2uiChip(
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
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    AssistChip(
        onClick = {
            action?.let {
                HapticEffects.contextClick(haptic, view)
                onAction(it)
            }
        },
        enabled = action != null && !surfaceSubmitting,
        label = { Text(label) },
        modifier = modifier.testTag(A2uiTestTags.Chip),
    )
}

@Composable
internal fun A2uiFilterChip(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["checked"] ?: component.raw["selected"]
    val effectivePath = component.inputPath(binding, renderScope)
    val observedAtPath by surface.dataModel.observe(effectivePath)
    val defaultSelected = component.resolveInputValue(surface, binding, renderScope).toBooleanStrictOrNull() ?: false
    val selected = observedAtPath?.let(A2uiBindingResolver::displayText)?.toBooleanStrictOrNull() ?: defaultSelected
    val label = component.resolveControlLabel(surface, renderScope)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    if (label == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingText))
        return
    }

    fun update(next: Boolean) {
        if (next != selected) {
            if (next) HapticEffects.toggleOn(haptic, view) else HapticEffects.toggleOff(haptic, view)
        }
        surface.dataModel.applyPatch(path = effectivePath, value = JsonPrimitive(next))
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
internal fun A2uiBadge(
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
internal fun A2uiTabs(
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
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

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
                    onClick = {
                        if (selectedIndex != index) HapticEffects.segmentTick(haptic, view)
                        selectedIndexState.value = index
                    },
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
internal fun A2uiAccordion(
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
                            text = if (expandedState.value) "âˆ’" else "+",
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
internal fun A2uiDropdown(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["selected"]
    val effectivePath = component.inputPath(binding, renderScope)
    val observedAtPath by surface.dataModel.observe(effectivePath)
    val defaultValue = component.resolveInputValue(surface, binding, renderScope)
    val value = observedAtPath?.let(A2uiBindingResolver::displayText) ?: defaultValue
    val label = component.resolveControlLabel(surface, renderScope)
    val placeholder = resolveBindingText(component.raw["placeholder"], surface, renderScope)
    val validation = component.raw.stringValue("validationRegexp")
    val options = component.resolveRadioOptions(surface, renderScope)
    val selectedLabel = options.firstOrNull { it.key == value }?.label.orEmpty()
    val isError = validation != null && value.isNotBlank() && !value.matchesValidation(validation)
    var expanded by remember(component.id) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    fun update(next: String) {
        if (next != value) HapticEffects.segmentTick(haptic, view)
        surface.dataModel.applyPatch(path = effectivePath, value = JsonPrimitive(next))
        expanded = false
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (!surfaceSubmitting) {
                if (it && !expanded) HapticEffects.contextClick(haptic, view)
                expanded = it
            }
        },
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
internal fun A2uiSlider(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"]
    val effectivePath = component.inputPath(binding, renderScope)
    val range = component.numericRange()
    val observedAtPath by surface.dataModel.observe(effectivePath)
    val defaultValue = component.resolveNumericInputValue(surface, binding, renderScope, range.min)
    val value = observedAtPath
        ?.let(A2uiBindingResolver::displayText)
        ?.toDoubleOrNull()
        ?: defaultValue
    val label = component.resolveControlLabel(surface, renderScope)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var dragStartValue by remember(component.id) { mutableStateOf<Double?>(null) }
    var lastTickValue by remember(component.id) { mutableStateOf(range.coerce(value)) }

    fun update(next: Double) {
        val stepped = range.coerce(next)
        if (stepped != lastTickValue) {
            HapticEffects.segmentTick(haptic, view)
            lastTickValue = stepped
        }
        surface.dataModel.applyPatch(path = effectivePath, value = stepped.numericJsonPrimitive(range.integralStep))
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
            onValueChange = {
                if (dragStartValue == null) {
                    val current = range.coerce(value)
                    dragStartValue = current
                    lastTickValue = current
                }
                update(it.toDouble())
            },
            onValueChangeFinished = {
                val startedAt = dragStartValue
                if (startedAt != null && lastTickValue != startedAt) {
                    HapticEffects.confirm(haptic, view)
                }
                dragStartValue = null
            },
            modifier = Modifier.testTag(A2uiTestTags.Slider),
            enabled = !surfaceSubmitting,
            valueRange = range.min.toFloat()..range.max.toFloat(),
            steps = range.sliderSteps,
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                )
            },
        )
    }
}

@Composable
internal fun A2uiStepper(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"]
    val effectivePath = component.inputPath(binding, renderScope)
    val range = component.numericRange()
    val observedAtPath by surface.dataModel.observe(effectivePath)
    val defaultValue = component.resolveNumericInputValue(surface, binding, renderScope, range.min)
    val value = observedAtPath
        ?.let(A2uiBindingResolver::displayText)
        ?.toDoubleOrNull()
        ?: defaultValue
    val label = component.resolveControlLabel(surface, renderScope)

    fun update(next: Double) {
        val stepped = range.coerce(next)
        surface.dataModel.applyPatch(path = effectivePath, value = stepped.numericJsonPrimitive(range.integralStep))
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
            Text("âˆ’")
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
internal fun A2uiLinearProgress(
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
internal fun A2uiCircularProgress(
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
