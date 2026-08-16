package com.letta.mobile.data.a2ui

import com.letta.mobile.data.model.UiGeneratedComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val GENERATED_UI_ROOT_COMPONENT_ID = "root"

/**
 * Adapts the legacy `generate_ui` tool contract ([UiGeneratedComponent]: a
 * single component name plus a raw JSON props bag — see
 * `DomainToUiMessageMapper.extractGeneratedUi`, wire shape
 * `{"type":"generated_ui","component":"<name>","props":{...},"text":"..."}`)
 * onto a single-node A2UI surface, so a desktop `GeneratedUiCard` or
 * standalone A2UI host (letta-mobile-2don7) can render it through the real
 * Basic-catalog renderer ([A2uiSurfaceRenderer]) instead of printing raw
 * JSON.
 *
 * This is deliberately conservative: it returns `null` — callers should fall
 * back to [UiGeneratedComponent.fallbackText] (never a silent blank) — unless
 * BOTH of the following hold:
 *  - [UiGeneratedComponent.name] is a widget id the renderer actually
 *    dispatches on ([A2UI_DEFAULT_SUPPORTED_WIDGETS]); an arbitrary
 *    human-readable card name (as used by desktop's preview/demo
 *    conversations in `DesktopChatModels.kt`) is not a catalog widget and is
 *    intentionally left to the fallback-text path.
 *  - [UiGeneratedComponent.propsJson] parses to a JSON object, which becomes
 *    the single component's raw props exactly as the wire protocol's
 *    `updateComponents.components[].{other fields}` would.
 */
fun UiGeneratedComponent.toA2uiSurfaceStateOrNull(): A2uiSurfaceState? {
    if (name !in A2UI_DEFAULT_SUPPORTED_WIDGETS) return null
    val props = runCatching { Json.parseToJsonElement(propsJson) }.getOrNull() as? JsonObject ?: return null
    val component = A2uiComponent(id = GENERATED_UI_ROOT_COMPONENT_ID, component = name, raw = props)
    return A2uiSurfaceState(
        surfaceId = "generated-ui:$name",
        rootComponentId = GENERATED_UI_ROOT_COMPONENT_ID,
        components = mapOf(GENERATED_UI_ROOT_COMPONENT_ID to component),
    )
}
