package com.letta.mobile.ui.a2ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiJsonPointer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull


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
