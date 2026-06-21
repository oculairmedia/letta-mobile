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
import java.util.concurrent.ConcurrentHashMap


@Composable
internal fun A2uiSkeletonCard(modifier: Modifier = Modifier) {
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
internal fun A2uiSkeletonLine(
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
internal fun A2uiSkeletonImage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
internal fun A2uiComponent.textStyle(): TextStyle = when (raw.stringValue("variant")) {
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
internal fun A2uiComponent.resolveText(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? {
    val binding = raw["text"] ?: raw["content"] ?: raw["value"]
    return resolveBindingText(binding, surface, renderScope)
}

@Composable
internal fun A2uiComponent.resolveButtonLabel(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? {
    // letta-mobile-njzb: A2UI v0.9 Basic Catalog defines Button.child as
    // "the ID of the child component. Use a Text component for a labeled
    // button." Resolve `child` first â€” that's the canonical spec field
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
internal fun A2uiComponent.resolveControlLabel(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    resolveBindingText(raw["label"] ?: raw["text"] ?: raw["title"], surface, renderScope)

@Composable
internal fun A2uiComponent.resolveProgressLabel(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? =
    resolveBindingText(raw["label"] ?: raw["text"] ?: raw["title"], surface, renderScope)

@Composable
internal fun A2uiComponent.resolveRadioOptions(
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
internal fun A2uiComponent.resolveTabs(
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

internal fun A2uiComponent.defaultTabIndex(items: List<A2uiTabItem>): Int {
    val default = raw.stringValue("default", "defaultKey", "selected", "value") ?: return 0
    return default.toIntOrNull()?.coerceIn(0, items.lastIndex)
        ?: items.indexOfFirst { it.key == default }.takeIf { it >= 0 }
        ?: 0
}

@Composable
internal fun A2uiComponent.resolveAccordionItems(
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
internal fun A2uiComponent.radioOptionFromElement(
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
internal fun resolveBindingText(
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
internal fun resolveBindingElement(
    binding: JsonElement?,
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
): JsonElement? =
    when {
        binding is JsonObject && binding.stringValue("path") != null -> {
            val value by surface.dataModel.observe(renderScope.resolvePath(binding.stringValue("path").orEmpty()))
            value
        }
        else -> when (val resolved = A2uiBindingResolver.resolve(binding?.withScopedPaths(renderScope), surface.dataModel)) {
            A2uiResolvedBinding.Missing -> null
            is A2uiResolvedBinding.Value -> resolved.value
        }
    }

@Composable
internal fun A2uiComponent.resolveInputValue(
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

internal fun A2uiComponent.action(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): A2uiAction? {
    val action = (raw["action"] ?: raw["onClick"]) as? JsonObject ?: return null
    // letta-mobile-ykkl: A2UI v0.9 Basic Catalog declares Button.action
    // as `Action` whose payload is nested under `event` (Action.event).
    // The pre-spec shape was `action: { name: â€¦ }` which we still accept
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
    //   (a) Spec: agent sets createSurface.sendDataModel:true â†’ always attach.
    //   (b) Safety net: agent's declared context is empty but the surface
    //       has data â†’ attach anyway so the agent gets the inputs even
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

internal fun A2uiComponent.localOpenUrl(surface: A2uiSurfaceState, renderScope: A2uiRenderScope): String? {
    val action = (raw["action"] ?: raw["onClick"]) as? JsonObject ?: return null
    val functionCall = (action["functionCall"] as? JsonObject)
        ?: action.takeIf { it.stringValue("call") == "openUrl" }
        ?: return null
    if (functionCall.stringValue("call") != "openUrl") return null
    val resolved = A2uiBindingResolver.resolve(functionCall.withScopedPaths(renderScope), surface.dataModel)
        as? A2uiResolvedBinding.Value
        ?: return null
    return A2uiBindingResolver.displayText(resolved.value)
        .trim()
        .takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}

internal fun A2uiComponent.spacing(): Dp =
    raw.dpValue("spacing") ?: when (raw.stringValue("spacing")) {
        "none" -> 0.dp
        "xs" -> 4.dp
        "sm" -> 8.dp
        "md" -> 12.dp
        "lg" -> 16.dp
        "xl" -> 24.dp
        else -> 8.dp
    }

internal fun A2uiComponent.horizontalArrangement(): Arrangement.Horizontal =
    when (raw.stringValue("justify", "distribution")) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spaceBetween" -> Arrangement.SpaceBetween
        "spaceAround" -> Arrangement.SpaceAround
        "spaceEvenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing())
    }

internal fun A2uiComponent.verticalAlignment(): Alignment.Vertical =
    when (raw.stringValue("align", "alignment")) {
        "top", "start" -> Alignment.Top
        "bottom", "end" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }

internal fun A2uiComponent.weightRowChildren(): Boolean =
    raw.booleanValue("equalWidth") ?: (raw.stringValue("justify", "distribution") !in setOf(
        "spaceBetween",
        "spaceAround",
        "spaceEvenly",
    ))

internal fun A2uiComponent.cornerRadius(): Dp =
    raw.dpValue("cornerRadius", "corner_radius") ?: 12.dp

internal fun A2uiComponent.elevation(): Dp =
    raw.dpValue("elevation") ?: 1.dp

internal fun A2uiComponent.height(): Dp? =
    raw.dpValue("height")

internal fun A2uiComponent.aspectRatioModifier(): Modifier =
    raw.stringValue("aspectRatio")
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }
        ?.let { Modifier.aspectRatio(it) }
        ?: Modifier

internal fun A2uiComponent.contentScale(): ContentScale =
    when (raw.stringValue("fit", "contentScale")) {
        "contain", "fit" -> ContentScale.Fit
        "fill", "stretch" -> ContentScale.FillBounds
        "none" -> ContentScale.None
        else -> ContentScale.Crop
    }

internal fun A2uiComponent.inputValue(
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
internal fun A2uiComponent.resolveNumericInputValue(
    surface: A2uiSurfaceState,
    binding: JsonElement?,
    renderScope: A2uiRenderScope,
    defaultValue: Double,
): Double = resolveInputValue(surface, binding, renderScope).toDoubleOrNull() ?: defaultValue

internal fun A2uiComponent.numericRange(): A2uiNumericRange {
    val min = raw.doubleValue("min", "minimum") ?: 0.0
    val rawMax = raw.doubleValue("max", "maximum") ?: 100.0
    val max = rawMax.takeIf { it > min } ?: (min + 1.0)
    val rawStep = raw.doubleValue("step") ?: 1.0
    val step = rawStep.takeIf { it > 0.0 } ?: 1.0
    return A2uiNumericRange(min = min, max = max, step = step)
}

internal fun A2uiNumericRange.coerce(value: Double): Double {
    val constrained = value.coerceIn(min, max)
    val snapped = min + (((constrained - min) / step).roundToInt() * step)
    return snapped.coerceIn(min, max)
}

internal fun Double.numericJsonPrimitive(integral: Boolean): JsonPrimitive =
    if (integral) JsonPrimitive(roundToInt().toLong()) else JsonPrimitive(this)

internal fun Double.numericDisplay(integral: Boolean): String =
    if (integral) roundToInt().toString() else toString().trimEnd('0').trimEnd('.')


internal fun JsonElement?.progressFractionOrNull(): Float? =
    this?.let(A2uiBindingResolver::displayText)?.toFloatOrNull()?.coerceIn(0f, 1f)

internal fun Float.progressPercentLabel(): String = "${(this * 100f).roundToInt()}%"

internal fun A2uiComponent.dateTimePlaceholder(
    enableDate: Boolean,
    enableTime: Boolean,
): String =
    when {
        enableDate && enableTime -> "YYYY-MM-DD HH:mm"
        enableDate -> "YYYY-MM-DD"
        else -> "HH:mm"
    }

internal data class ToolApprovalProps(
    val toolName: String,
    val toolDescription: String?,
    val arguments: List<ToolApprovalArgument>,
    val risk: ToolApprovalRisk,
    val rationale: String?,
    val affordances: List<ToolApprovalAffordance>,
    val timeoutSeconds: Int,
    val callId: String,
)

internal data class ScheduleCardProps(
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

internal data class ScheduleSelectorValue(
    val mode: ScheduleSelectorMode,
    val value: String,
)

internal enum class ScheduleStatus(
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

internal enum class ScheduleSelectorMode(
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

internal enum class A2uiBooleanInputKind(val testTag: String) {
    Checkbox(A2uiTestTags.Checkbox),
    Switch(A2uiTestTags.Switch),
}

internal enum class A2uiMediaKind(val testTag: String) {
    Video(A2uiTestTags.Video),
    Audio(A2uiTestTags.AudioPlayer),
}

internal data class A2uiRadioOption(
    val key: String,
    val label: String,
)

internal data class A2uiTabItem(
    val key: String,
    val label: String,
    val childId: String,
)

internal data class A2uiAccordionItem(
    val key: String,
    val title: String,
    val childId: String,
    val defaultOpen: Boolean,
)

internal data class A2uiNumericRange(
    val min: Double,
    val max: Double,
    val step: Double,
) {
    val integralStep: Boolean = step % 1.0 == 0.0
    val sliderSteps: Int = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)
}

internal data class ToolApprovalArgument(
    val key: String,
    val value: String,
    val isSensitive: Boolean,
)

internal data class ToolApprovalResult(
    val decision: String,
    val scope: String,
)

internal enum class ToolApprovalRisk(
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

internal enum class ToolApprovalAffordance(
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

internal data class ToolApprovalRiskStyle(
    val borderColor: Color,
    val borderWidth: Dp,
    val pillContainer: Color,
    val pillContent: Color,
)

@Composable
internal fun ToolApprovalRisk.style(): ToolApprovalRiskStyle =
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

internal fun ToolApprovalResult.statusLabel(): String =
    when {
        decision == "timeout" -> "Timed out"
        decision == "deny" -> "Denied"
        scope == "once" -> "Approved once"
        scope == "session" -> "Approved for this chat"
        scope == "forever" -> "Approved always"
        else -> "Resolved"
    }

internal fun JsonObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitiveOrNull?.contentOrNull }

internal fun JsonObject.intValue(vararg keys: String): Int? =
    stringValue(*keys)?.toIntOrNull()

internal fun JsonObject.dpValue(vararg keys: String): Dp? =
    stringValue(*keys)?.toFloatOrNull()?.coerceIn(0f, 64f)?.dp

internal fun JsonObject.doubleValue(vararg keys: String): Double? =
    stringValue(*keys)?.toDoubleOrNull()

internal fun JsonObject.booleanValue(vararg keys: String): Boolean? =
    stringValue(*keys)?.toBooleanStrictOrNull()

internal fun JsonElement?.bindingPath(): String? =
    (this as? JsonObject)?.stringValue("path")

internal fun A2uiComponent.inputPath(
    binding: JsonElement?,
    renderScope: A2uiRenderScope,
): String =
    binding.bindingPath()?.let(renderScope::resolvePath) ?: syntheticInputPath()

internal fun A2uiComponent.syntheticInputPath(): String = "/_inputs/${id}"
internal fun JsonElement.withScopedPaths(renderScope: A2uiRenderScope): JsonElement =
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

internal fun String.appendJsonPointerSegment(segment: String): String =
    A2uiJsonPointer.normalize(this).trimEnd('/') + "/" + segment.escapeJsonPointerSegment()

internal fun String.escapeJsonPointerSegment(): String =
    replace("~", "~0").replace("/", "~1")

internal fun JsonElement.resolveItemKey(path: String): String? =
    A2uiJsonPointer.resolve(this, path)
        ?.let(A2uiBindingResolver::displayText)
        ?.takeIf { it.isNotBlank() }

@Composable
internal fun A2uiComponent.validationError(
    value: String,
    surface: A2uiSurfaceState,
    renderScope: A2uiRenderScope,
    legacyValidation: String?,
): String? {
    if (legacyValidation != null && value.isNotBlank() && !value.matchesValidation(legacyValidation)) {
        return "Invalid value"
    }
    val checks = raw["checks"] as? JsonArray ?: return null
    checks.forEach { check ->
        val checkObject = check as? JsonObject ?: return@forEach
        val functionCall = ((checkObject["functionCall"] as? JsonObject) ?: checkObject)
            .withValidationValue(value)
            .withScopedPaths(renderScope)
        val result = A2uiBindingResolver.resolve(functionCall, surface.dataModel)
        val passes = (result as? A2uiResolvedBinding.Value)?.value?.validationBoolean() ?: true
        if (!passes) {
            return resolveBindingText(checkObject["message"] ?: checkObject["error"], surface, renderScope)
                ?: "Invalid value"
        }
    }
    return null
}

internal fun JsonObject.withValidationValue(value: String): JsonObject {
    val args = this["args"] as? JsonObject ?: JsonObject(emptyMap())
    if ("value" in args) return this
    return JsonObject(this + ("args" to JsonObject(args + ("value" to JsonPrimitive(value)))))
}

internal fun JsonElement.validationBoolean(): Boolean = when (this) {
    is JsonPrimitive -> contentOrNull?.toBooleanStrictOrNull() ?: contentOrNull?.isNotBlank() == true
    is JsonArray -> isNotEmpty()
    is JsonObject -> isNotEmpty()
    else -> false
}

internal fun JsonElement?.choiceSelection(): Set<String> = when (this) {
    is JsonArray -> mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    is JsonPrimitive -> contentOrNull?.takeIf(String::isNotBlank)?.let { linkedSetOf(it) }.orEmpty()
    else -> emptySet()
}

private val validationRegexCache = ConcurrentHashMap<String, Regex>()

internal fun String.matchesValidation(pattern: String): Boolean =
    runCatching { validationRegexCache.getOrPut(pattern) { Regex(pattern) }.matches(this) }.getOrDefault(true)

internal fun String.toDateMillis(): Long? =
    runCatching {
        LocalDate.parse(take(DateIsoLength), DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }.getOrNull()

internal fun String.toLocalTime(): LocalTime? {
    val timeText = substringAfter('T', missingDelimiterValue = substringAfter(' ', missingDelimiterValue = this))
    return runCatching {
        LocalTime.parse(timeText.take(TimeIsoLength), DateTimeFormatter.ISO_LOCAL_TIME)
    }.getOrNull()
}

internal fun formatDateTime(
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

internal fun LocalTime?.orNow(): LocalTime =
    this ?: LocalTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)

internal val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

internal const val DateIsoLength = 10
internal const val TimeIsoLength = 5
internal val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal const val MaxRenderDepth = 32
internal const val DefaultToolApprovalTimeoutSeconds = 30
internal const val A2uiButtonLocalTimeoutMillis = 10_000L
internal const val A2uiLocalStateRootComponentId = "__surface__"
internal const val ChoicePickerSegmentedOptionLimit = 4
internal const val SensitiveMask = "********"
internal const val ToolApprovalResponseAction = "tool_approval_response"
internal const val ToolApprovalButtonsPerRow = 2
internal const val ScheduleSaveAction = "schedule.save"
internal const val ScheduleDeleteAction = "schedule.delete"
internal const val SchedulePauseAction = "schedule.pause"
internal const val ScheduleResumeAction = "schedule.resume"
internal const val ScheduleRunNowAction = "schedule.run_now"
