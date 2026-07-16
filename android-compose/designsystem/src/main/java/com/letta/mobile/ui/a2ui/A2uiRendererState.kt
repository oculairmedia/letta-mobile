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

internal data class A2uiLocalStateKey(
    val surfaceId: String,
    val componentId: String,
    val key: String,
)
@Composable
internal fun rememberA2uiLocalIntState(
    key: String,
    initialValue: Int,
): MutableState<Int> {
    val scope = LocalA2uiLocalState.current
    return scope?.intState(key, initialValue) ?: remember(key) { mutableStateOf(initialValue) }
}

@Composable
internal fun rememberA2uiLocalBooleanState(
    key: String,
    initialValue: Boolean,
): MutableState<Boolean> {
    val scope = LocalA2uiLocalState.current
    return scope?.booleanState(key, initialValue) ?: remember(key) { mutableStateOf(initialValue) }
}

internal fun A2uiComponent.localStateDefaults(): JsonObject =
    raw["localState"] as? JsonObject ?: JsonObject(emptyMap())

internal data class A2uiRenderScope(
    val basePath: String? = null,
) {
    fun resolvePath(path: String): String {
        val normalized = A2uiJsonPointer.normalize(path)
        val base = basePath ?: return normalized
        return if (path.startsWith("/")) {
            normalized
        } else {
            base.appendJsonPointerSegment(path)
        }
    }

    companion object {
        val Root = A2uiRenderScope()
    }
}
