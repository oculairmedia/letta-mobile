package com.letta.mobile.data.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

fun resolveA2uiActionContext(
    context: JsonElement?,
    surface: A2uiSurfaceState,
): JsonObject = when (context) {
    null -> JsonObject(emptyMap())
    is JsonObject -> resolveContextObject(context, surface)
    is JsonArray -> resolveContextPairs(context, surface)
    else -> JsonObject(emptyMap())
}

// letta-mobile-lwmo: legacy overload kept for tests / external callers that
// still pass an A2uiDataModel directly. Template-string references like
// $<componentId>.value can't be resolved without component metadata, so they
// fall through unchanged on this path.
fun resolveA2uiActionContext(
    context: JsonElement?,
    dataModel: A2uiDataModel,
): JsonObject = resolveA2uiActionContext(
    context,
    A2uiSurfaceState(surfaceId = "", dataModel = dataModel),
)

private fun resolveContextObject(
    context: JsonObject,
    surface: A2uiSurfaceState,
): JsonObject = buildJsonObject {
    context.forEach { (key, value) ->
        put(key, resolveContextValue(value, surface))
    }
}

private fun resolveContextPairs(
    context: JsonArray,
    surface: A2uiSurfaceState,
): JsonObject = buildJsonObject {
    context.forEach { element ->
        val pair = element as? JsonObject ?: return@forEach
        val key = pair.stringValue("key", "name")?.takeIf { it.isNotBlank() } ?: return@forEach
        val value = pair["value"]
            ?: pair["binding"]
            ?: pair["path"]?.let { buildJsonObject { put("path", it) } }
            ?: pair["literalString"]?.let { buildJsonObject { put("literalString", it) } }
            ?: pair["literal"]?.let { buildJsonObject { put("literal", it) } }
            ?: JsonNull
        put(key, resolveContextValue(value, surface))
    }
}

private fun resolveContextValue(
    value: JsonElement,
    surface: A2uiSurfaceState,
): JsonElement = when (value) {
    is JsonObject -> when {
        value.isBindingObject() -> when (val resolved = A2uiBindingResolver.resolve(value, surface.dataModel)) {
            A2uiResolvedBinding.Missing -> JsonNull
            is A2uiResolvedBinding.Value -> resolved.value
        }
        else -> buildJsonObject {
            value.forEach { (key, nestedValue) ->
                put(key, resolveContextValue(nestedValue, surface))
            }
        }
    }
    is JsonArray -> JsonArray(value.map { resolveContextValue(it, surface) })
    is JsonPrimitive -> resolveComponentValueReference(value, surface) ?: value
    else -> value
}

// letta-mobile-lwmo: the shim's A2UI agent prompt emits Button.action.context
// values using a `$<componentId>.<field>` templating shorthand (e.g.
// "$note-field.value") instead of an A2UI v0.9 binding object. The renderer
// was passing those strings through unchanged, so the agent only saw the
// template, not the typed value. Recognize the shorthand and resolve it via
// the component's bound path against the surface data model.
// Both segments must be alpha-leading identifiers so price strings like
// "$5.00" or literal labels like "$." don't get hijacked as references.
private val ComponentValueReferenceRegex = Regex("""^\$([a-zA-Z][\w-]*)\.([a-zA-Z][\w-]*)$""")

private fun resolveComponentValueReference(
    primitive: JsonPrimitive,
    surface: A2uiSurfaceState,
): JsonElement? {
    if (!primitive.isString) return null
    val text = primitive.contentOrNull ?: return null
    val match = ComponentValueReferenceRegex.matchEntire(text) ?: return null
    // Matched the $<id>.<field> shorthand. Once we know it's a reference,
    // never leak the template string back to the wire — unknown component
    // or unbound field both resolve to JsonNull so the agent sees an
    // explicit "no value" rather than a confusing literal.
    val componentId = match.groupValues[1]
    val field = match.groupValues[2]
    val component = surface.components[componentId] ?: return JsonNull
    val binding = component.raw[field]
        ?: component.raw["value"]
        ?: component.raw["text"]
    // Mirror the renderer's input-write path (letta-mobile-lwmo): bound
    // inputs use the explicit binding path; unbound inputs write to the
    // synthetic /_inputs/<id> slot. Check the data model for either before
    // falling back to the binding's literal default.
    val explicitPath = (binding as? JsonObject)
        ?.get("path")
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    val effectivePath = explicitPath ?: "/_inputs/$componentId"
    surface.dataModel.resolve(effectivePath)?.let { return it }
    if (binding == null) return JsonNull
    return when (val resolved = A2uiBindingResolver.resolve(binding, surface.dataModel)) {
        A2uiResolvedBinding.Missing -> JsonNull
        is A2uiResolvedBinding.Value -> resolved.value
    }
}

private fun JsonObject.isBindingObject(): Boolean =
    "path" in this || "literalString" in this || "literal" in this || "value" in this

private fun JsonObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull
    }
