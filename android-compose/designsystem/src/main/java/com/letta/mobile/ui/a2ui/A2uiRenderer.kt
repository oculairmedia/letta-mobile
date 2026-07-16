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
    const val ChoicePicker = "a2ui_choice_picker"
    const val ChoicePickerChipOption = "a2ui_choice_picker_chip_option"
    const val ChoicePickerListOption = "a2ui_choice_picker_list_option"
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
    const val Icon = "a2ui_icon"
    const val MissingIcon = "a2ui_missing_icon"
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
    const val Modal = "a2ui_modal"
    const val Video = "a2ui_video"
    const val AudioPlayer = "a2ui_audio_player"
    const val MediaPlayPause = "a2ui_media_play_pause"
}

@Composable
internal fun A2uiComponentNode(
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
    androidx.compose.runtime.SideEffect {
        if (android.util.Log.isLoggable("A2UI", android.util.Log.DEBUG)) {
            android.util.Log.d(
                "A2UI",
                "Render component surfaceId=${surface.surfaceId} componentId=${component.id} type=${component.component}",
            )
        }
    }
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
        "Checkbox", "CheckBox" -> A2uiBooleanInput(
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
        "ChoicePicker" -> A2uiChoicePicker(
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
        "Icon" -> A2uiIcon(component = component, surface = surface, modifier = modifier, renderScope = renderScope)
        "Divider" -> A2uiDivider(component = component, modifier = modifier)
        "Video" -> A2uiMedia(
            component = component,
            surface = surface,
            modifier = modifier,
            renderScope = renderScope,
            kind = A2uiMediaKind.Video,
        )
        "AudioPlayer" -> A2uiMedia(
            component = component,
            surface = surface,
            modifier = modifier,
            renderScope = renderScope,
            kind = A2uiMediaKind.Audio,
        )
        "Modal" -> A2uiModal(
            component = component,
            surface = surface,
            visited = nextVisited,
            onAction = onAction,
            surfaceSubmitting = surfaceSubmitting,
            onPendingActionDelta = onPendingActionDelta,
            actionResolutionToken = actionResolutionToken,
            renderScope = renderScope,
        )
        A2UI_LIST_VIEW_WIDGET_ID, "List" -> A2uiListView(
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
