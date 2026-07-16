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
import androidx.compose.material3.Icon
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
import com.letta.mobile.ui.icons.LettaIcons
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
internal fun A2uiText(
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
internal fun A2uiTextField(
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
        // Bound surfaces: existing behavior â€” read from the observed binding.
        literalDefault
    } else {
        // Unbound surfaces: synthetic data-model slot drives display; literal
        // binding (if any) is the initial default until the user types.
        observedAtPath?.let(A2uiBindingResolver::displayText) ?: literalDefault
    }
    val validationError = component.validationError(
        value = value,
        surface = surface,
        renderScope = renderScope,
        legacyValidation = validation,
    )
    val isError = validationError != null

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
            isError -> ({ Text(validationError.orEmpty()) })
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
internal fun A2uiBooleanInput(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
    kind: A2uiBooleanInputKind,
) {
    val binding = component.raw["value"] ?: component.raw["checked"] ?: component.raw["selected"]
    val effectivePath = component.inputPath(binding, renderScope)
    val observedAtPath by surface.dataModel.observe(effectivePath)
    val defaultChecked = component.resolveInputValue(surface, binding, renderScope).toBooleanStrictOrNull() ?: false
    val checked = observedAtPath?.let(A2uiBindingResolver::displayText)?.toBooleanStrictOrNull() ?: defaultChecked
    val label = component.resolveControlLabel(surface, renderScope)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    fun update(next: Boolean) {
        if (next != checked) {
            if (next) HapticEffects.toggleOn(haptic, view) else HapticEffects.toggleOff(haptic, view)
        }
        surface.dataModel.applyPatch(path = effectivePath, value = JsonPrimitive(next))
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
internal fun A2uiRadio(
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
    val options = component.resolveRadioOptions(surface, renderScope)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    if (options.isEmpty()) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    fun update(next: String) {
        if (next != value) HapticEffects.segmentTick(haptic, view)
        surface.dataModel.applyPatch(path = effectivePath, value = JsonPrimitive(next))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun A2uiChoicePicker(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    surfaceSubmitting: Boolean,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["selected"]
    val explicitPath = binding.bindingPath()?.let(renderScope::resolvePath)
    val effectivePath = explicitPath ?: "/_inputs/${component.id}"
    val literalDefault = resolveBindingElement(binding, surface, renderScope)
    val observedAtPath by surface.dataModel.observe(effectivePath)
    var localSelection by remember(component.id) { mutableStateOf(literalDefault.choiceSelection()) }
    val selected = observedAtPath.choiceSelection().ifEmpty {
        if (explicitPath != null) literalDefault.choiceSelection() else localSelection
    }
    val label = component.resolveControlLabel(surface, renderScope)
    val options = component.resolveRadioOptions(surface, renderScope)
    val selectionMode = component.raw.stringValue("selectionMode", "selection").orEmpty().lowercase()
    val multiSelect = selectionMode == "multi" || selectionMode == "multiple"
    val displayMode = component.raw.stringValue("displayMode", "mode").orEmpty().lowercase()
    val useChips = when (displayMode) {
        "chips", "chip" -> true
        "checkbox", "radio", "list" -> false
        else -> options.size <= ChoicePickerSegmentedOptionLimit
    }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    if (options.isEmpty()) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    fun update(next: String) {
        val nextSelection = if (multiSelect) {
            if (next in selected) selected - next else selected + next
        } else {
            linkedSetOf(next)
        }
        if (nextSelection != selected) HapticEffects.segmentTick(haptic, view)
        val nextValue = if (multiSelect) {
            JsonArray(nextSelection.map(::JsonPrimitive))
        } else {
            JsonPrimitive(nextSelection.firstOrNull().orEmpty())
        }
        surface.dataModel.applyPatch(path = effectivePath, value = nextValue)
        if (explicitPath == null) {
            localSelection = nextSelection
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (surfaceSubmitting) Modifier.semantics { disabled() } else Modifier)
            .testTag(A2uiTestTags.ChoicePicker),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (useChips) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option.key in selected,
                        onClick = { update(option.key) },
                        enabled = !surfaceSubmitting,
                        modifier = Modifier.testTag(A2uiTestTags.ChoicePickerChipOption),
                        label = {
                            Text(
                                text = option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        } else {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(A2uiTestTags.ChoicePickerListOption)
                        .clickable(enabled = !surfaceSubmitting) { update(option.key) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (multiSelect) {
                        Checkbox(
                            checked = option.key in selected,
                            onCheckedChange = { update(option.key) },
                            enabled = !surfaceSubmitting,
                        )
                    } else {
                        RadioButton(
                            selected = option.key in selected,
                            onClick = { update(option.key) },
                            enabled = !surfaceSubmitting,
                        )
                    }
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
}

@Composable
internal fun A2uiDateTimeInput(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    renderScope: A2uiRenderScope,
) {
    val binding = component.raw["value"] ?: component.raw["text"]
    val effectivePath = component.inputPath(binding, renderScope)
    val observedAtPath by surface.dataModel.observe(effectivePath)
    val value = observedAtPath?.let(A2uiBindingResolver::displayText)
        ?: component.resolveInputValue(surface, binding, renderScope)
    val label = resolveBindingText(component.raw["label"], surface, renderScope) ?: "Date and time"
    val enableDate = component.raw.booleanValue("enableDate") ?: true
    val enableTime = component.raw.booleanValue("enableTime") ?: false
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf(value.toDateMillis()) }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
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
                    HapticEffects.contextClick(haptic, view)
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
                            HapticEffects.contextClick(haptic, view)
                            showTimePicker = true
                        } else {
                            surface.dataModel.applyPatch(
                                path = effectivePath,
                                value = JsonPrimitive(formatDateTime(pendingDateMillis, null, enableDate, false)),
                            )
                            HapticEffects.confirm(haptic, view)
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
                        surface.dataModel.applyPatch(
                            path = effectivePath,
                            value = JsonPrimitive(
                                formatDateTime(
                                    dateMillis = pendingDateMillis,
                                    time = LocalTime.of(timePickerState.hour, timePickerState.minute),
                                    enableDate = enableDate,
                                    enableTime = enableTime,
                                ),
                            ),
                        )
                        HapticEffects.confirm(haptic, view)
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
internal fun A2uiImage(
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

private val A2uiLettaIconsByName = mapOf(
    "ArrowBack" to LettaIcons.ArrowBack,
    "Close" to LettaIcons.Close,
    "Menu" to LettaIcons.Menu,
    "ChevronRight" to LettaIcons.ChevronRight,
    "ChevronDown" to LettaIcons.ChevronDown,
    "ChevronUp" to LettaIcons.ChevronUp,
    "ExpandMore" to LettaIcons.ExpandMore,
    "ExpandLess" to LettaIcons.ExpandLess,
    "ArrowDropDown" to LettaIcons.ArrowDropDown,
    "ArrowDropUp" to LettaIcons.ArrowDropUp,
    "KeyboardArrowDown" to LettaIcons.KeyboardArrowDown,
    "ListIcon" to LettaIcons.ListIcon,
    "Add" to LettaIcons.Add,
    "Edit" to LettaIcons.Edit,
    "Delete" to LettaIcons.Delete,
    "Save" to LettaIcons.Save,
    "Search" to LettaIcons.Search,
    "Clear" to LettaIcons.Clear,
    "Refresh" to LettaIcons.Refresh,
    "Send" to LettaIcons.Send,
    "Copy" to LettaIcons.Copy,
    "Share" to LettaIcons.Share,
    "MoreVert" to LettaIcons.MoreVert,
    "Play" to LettaIcons.Play,
    "ManageSearch" to LettaIcons.ManageSearch,
    "Check" to LettaIcons.Check,
    "CheckCircle" to LettaIcons.CheckCircle,
    "Error" to LettaIcons.Error,
    "Info" to LettaIcons.Info,
    "Help" to LettaIcons.Help,
    "Circle" to LettaIcons.Circle,
    "Agent" to LettaIcons.Agent,
    "Tool" to LettaIcons.Tool,
    "Chat" to LettaIcons.Chat,
    "ChatOutline" to LettaIcons.ChatOutline,
    "Settings" to LettaIcons.Settings,
    "Key" to LettaIcons.Key,
    "People" to LettaIcons.People,
    "Cloud" to LettaIcons.Cloud,
    "Storage" to LettaIcons.Storage,
    "Code" to LettaIcons.Code,
    "Schema" to LettaIcons.Schema,
    "Dashboard" to LettaIcons.Dashboard,
    "Apps" to LettaIcons.Apps,
    "ViewModule" to LettaIcons.ViewModule,
    "FileOpen" to LettaIcons.FileOpen,
    "Inventory" to LettaIcons.Inventory,
    "Archive" to LettaIcons.Archive,
    "ForkRight" to LettaIcons.ForkRight,
    "Database" to LettaIcons.Database,
    "Favorite" to LettaIcons.Favorite,
    "FavoriteBorder" to LettaIcons.FavoriteBorder,
    "Star" to LettaIcons.Star,
    "Sparkles" to LettaIcons.Sparkles,
    "AutoAwesome" to LettaIcons.AutoAwesome,
    "Lightbulb" to LettaIcons.Lightbulb,
    "Psychology" to LettaIcons.Psychology,
    "AccountCircle" to LettaIcons.AccountCircle,
    "AccessTime" to LettaIcons.AccessTime,
    "Link" to LettaIcons.Link,
    "LinkOff" to LettaIcons.LinkOff,
    "ExternalLink" to LettaIcons.ExternalLink,
    "Pin" to LettaIcons.Pin,
    "PinOff" to LettaIcons.PinOff,
).mapKeys { (name, _) -> name.a2uiIconKey() }

@Composable
internal fun A2uiIcon(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    renderScope: A2uiRenderScope,
) {
    val name = resolveBindingText(component.raw["name"] ?: component.raw["icon"], surface, renderScope)
        ?.takeIf { it.isNotBlank() }
    val size = component.iconSize()
    val imageVector = name?.let(::lookupA2uiIcon)

    if (name == null || imageVector == null) {
        LaunchedEffect(component.id, name) {
            android.util.Log.w(
                "A2UI",
                "Unsupported A2UI Icon name=${name.orEmpty()} componentId=${component.id}",
            )
        }
        A2uiSkeletonIcon(
            modifier = modifier.testTag(A2uiTestTags.MissingIcon),
            size = size,
        )
        return
    }

    val contentDescription = resolveBindingText(
        component.raw["contentDescription"] ?: component.raw["description"] ?: component.raw["label"],
        surface,
        renderScope,
    ) ?: name
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .testTag(A2uiTestTags.Icon),
        tint = component.iconTint(),
    )
}

@Composable
private fun A2uiSkeletonIcon(
    modifier: Modifier = Modifier,
    size: Dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
    )
}

private fun A2uiComponent.iconSize(): Dp =
    raw.dpValue("sizeDp", "size_dp", "dp") ?: when (raw.stringValue("size")?.trim()?.lowercase()) {
        "xs" -> 12.dp
        "sm" -> 16.dp
        "md", null, "" -> 24.dp
        "lg" -> 32.dp
        else -> raw.dpValue("size") ?: 24.dp
    }

@Composable
private fun A2uiComponent.iconTint(): Color =
    when (raw.stringValue("tint", "color")?.trim()) {
        "primary" -> MaterialTheme.colorScheme.primary
        "onPrimary" -> MaterialTheme.colorScheme.onPrimary
        "primaryContainer" -> MaterialTheme.colorScheme.primaryContainer
        "onPrimaryContainer" -> MaterialTheme.colorScheme.onPrimaryContainer
        "secondary" -> MaterialTheme.colorScheme.secondary
        "onSecondary" -> MaterialTheme.colorScheme.onSecondary
        "secondaryContainer" -> MaterialTheme.colorScheme.secondaryContainer
        "onSecondaryContainer" -> MaterialTheme.colorScheme.onSecondaryContainer
        "tertiary" -> MaterialTheme.colorScheme.tertiary
        "onTertiary" -> MaterialTheme.colorScheme.onTertiary
        "tertiaryContainer" -> MaterialTheme.colorScheme.tertiaryContainer
        "onTertiaryContainer" -> MaterialTheme.colorScheme.onTertiaryContainer
        "error" -> MaterialTheme.colorScheme.error
        "onError" -> MaterialTheme.colorScheme.onError
        "errorContainer" -> MaterialTheme.colorScheme.errorContainer
        "onErrorContainer" -> MaterialTheme.colorScheme.onErrorContainer
        "surface" -> MaterialTheme.colorScheme.surface
        "onSurface" -> MaterialTheme.colorScheme.onSurface
        "surfaceVariant" -> MaterialTheme.colorScheme.surfaceVariant
        "onSurfaceVariant", null, "" -> MaterialTheme.colorScheme.onSurfaceVariant
        "outline" -> MaterialTheme.colorScheme.outline
        "outlineVariant" -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun lookupA2uiIcon(name: String) = A2uiLettaIconsByName[name.a2uiIconKey()]

private fun String.a2uiIconKey(): String =
    filter { it.isLetterOrDigit() }.lowercase()

@Composable
internal fun A2uiDivider(
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
internal fun A2uiColumn(
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
internal fun A2uiRow(
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
internal fun A2uiListView(
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
        val children = component.children
        if (children.isEmpty()) {
            A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
            return
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .testTag(A2uiTestTags.ListView),
            verticalArrangement = Arrangement.spacedBy(component.spacing()),
        ) {
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
internal fun A2uiModal(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
    surfaceSubmitting: Boolean,
    onPendingActionDelta: (Int) -> Unit,
    actionResolutionToken: Int,
    renderScope: A2uiRenderScope,
) {
    val visibleText = resolveBindingText(
        component.raw["visible"] ?: component.raw["isVisible"] ?: component.raw["open"],
        surface,
        renderScope,
    )
    val visible = visibleText?.toBooleanStrictOrNull()
        ?: component.raw.booleanValue("visible", "isVisible", "open")
        ?: false
    var dismissed by remember(component.id) { mutableStateOf(false) }
    if (!visible || dismissed) return

    val child = component.child ?: component.children.firstOrNull()
    AlertDialog(
        onDismissRequest = { dismissed = true },
        modifier = Modifier.testTag(A2uiTestTags.Modal),
        title = resolveBindingText(component.raw["title"], surface, renderScope)?.let { title ->
            { Text(title) }
        },
        text = {
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
        },
        confirmButton = {
            TextButton(onClick = { dismissed = true }) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun A2uiMedia(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    renderScope: A2uiRenderScope,
    kind: A2uiMediaKind,
) {
    val source = resolveBindingText(
        component.raw["url"] ?: component.raw["src"] ?: component.raw["source"],
        surface,
        renderScope,
    )?.takeIf { it.isNotBlank() }

    if (source == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    var playing by remember(component.id, source) { mutableStateOf(false) }
    val title = resolveBindingText(component.raw["title"] ?: component.raw["label"], surface, renderScope)
        ?: if (kind == A2uiMediaKind.Video) "Video" else "Audio"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(kind.testTag),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (kind == A2uiMediaKind.Video) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (playing) "Playing" else "Paused",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { playing = !playing },
                    modifier = Modifier.testTag(A2uiTestTags.MediaPlayPause),
                ) {
                    Text(if (playing) "Pause" else "Play")
                }
                Slider(
                    value = if (playing) 0.12f else 0f,
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "0:00",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = source,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun A2uiCard(
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
internal fun A2uiButton(
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
    val localOpenUrl = component.localOpenUrl(surface, renderScope)
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
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
            if (localOpenUrl != null) {
                android.util.Log.i(
                    "A2UI",
                    "Button onClick: opening URL surfaceId=${surface.surfaceId} " +
                        "componentId=${component.id} url=$localOpenUrl",
                )
                HapticEffects.confirm(haptic, view)
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(localOpenUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { error ->
                    android.util.Log.w("A2UI", "Failed to open URL for A2UI Button", error)
                }
                return@Button
            }
            // letta-mobile-ykkl diagnostic: log the dispatch hop so the
            // chain "Compose onClick â†’ onAction â†’ WsChatBridge â†’ wire"
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
                HapticEffects.confirm(haptic, view)
                inFlight = true
                onPendingActionDelta(1)
                onAction(resolved)
            }
        },
        enabled = label != null && (action != null || localOpenUrl != null) && !inFlight,
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
