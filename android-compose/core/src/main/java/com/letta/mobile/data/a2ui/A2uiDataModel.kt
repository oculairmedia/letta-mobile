package com.letta.mobile.data.a2ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Stable
class A2uiDataModel(
    initialRoot: JsonElement = EmptyJsonObject,
) {
    private val rootState: MutableState<JsonElement> = mutableStateOf(initialRoot)
    private val observedPaths = LinkedHashMap<String, MutableState<JsonElement?>>()

    val root: JsonElement
        get() = rootState.value

    fun resolve(path: String): JsonElement? = A2uiJsonPointer.resolve(root, path)

    fun observe(path: String): State<JsonElement?> {
        val normalizedPath = A2uiJsonPointer.normalize(path)
        return synchronized(observedPaths) {
            observedPaths.getOrPut(normalizedPath) {
                mutableStateOf(A2uiJsonPointer.resolve(root, normalizedPath))
            }
        }
    }

    fun applyPatch(
        path: String,
        value: JsonElement?,
    ): Boolean {
        val nextRoot = A2uiJsonPointer.applyPatch(root, path, value)
        if (nextRoot == root) return false
        rootState.value = nextRoot
        refreshObservedPaths()
        return true
    }

    private fun refreshObservedPaths() {
        synchronized(observedPaths) {
            observedPaths.forEach { (path, state) ->
                val nextValue = A2uiJsonPointer.resolve(root, path)
                if (state.value != nextValue) {
                    state.value = nextValue
                }
            }
        }
    }
}

object A2uiJsonPointer {
    fun normalize(path: String): String {
        val trimmed = path.trim()
        return when {
            trimmed.isBlank() || trimmed == "/" -> "/"
            trimmed.startsWith("/") -> trimmed
            else -> "/$trimmed"
        }
    }

    fun resolve(
        dataModel: JsonElement,
        path: String,
    ): JsonElement? {
        val normalizedPath = normalize(path)
        if (normalizedPath == "/") return dataModel
        return segments(normalizedPath).fold<JsonPointerSegment, JsonElement?>(dataModel) { current, segment ->
            when (current) {
                is JsonObject -> current[segment.value]
                is JsonArray -> segment.value.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
        }
    }

    fun applyPatch(
        dataModel: JsonElement,
        path: String,
        value: JsonElement?,
    ): JsonElement {
        val normalizedPath = normalize(path)
        if (normalizedPath == "/") return value ?: EmptyJsonObject
        val segments = segments(normalizedPath)
        return if (value == null) {
            removeAtPath(dataModel, segments)
        } else {
            setAtPath(dataModel, segments, value)
        }
    }

    private fun segments(path: String): List<JsonPointerSegment> =
        path.removePrefix("/")
            .split("/")
            .filter { it.isNotEmpty() }
            .map { JsonPointerSegment(it.replace("~1", "/").replace("~0", "~")) }

    private fun setAtPath(
        current: JsonElement,
        segments: List<JsonPointerSegment>,
        value: JsonElement,
    ): JsonElement {
        if (segments.isEmpty()) return mergeValue(current, value)
        val segment = segments.first().value
        val remaining = segments.drop(1)
        return when (current) {
            is JsonArray -> setArrayPath(current, segment, remaining, value)
            is JsonObject -> setObjectPath(current, segment, remaining, value)
            else -> {
                val replacement = containerFor(segment)
                setAtPath(replacement, segments, value)
            }
        }
    }

    private fun setObjectPath(
        current: JsonObject,
        segment: String,
        remaining: List<JsonPointerSegment>,
        value: JsonElement,
    ): JsonElement {
        val existingChild = current[segment]
        val nextChild = if (remaining.isEmpty()) {
            existingChild?.let { mergeValue(it, value) } ?: value
        } else {
            setAtPath(existingChild ?: containerFor(remaining.first().value), remaining, value)
        }
        return JsonObject(current + (segment to nextChild))
    }

    private fun setArrayPath(
        current: JsonArray,
        segment: String,
        remaining: List<JsonPointerSegment>,
        value: JsonElement,
    ): JsonElement {
        val index = when (segment) {
            "-" -> current.size
            else -> segment.toIntOrNull()?.takeIf { it >= 0 } ?: return current
        }
        val mutable = current.toMutableList()
        while (mutable.size < index) {
            mutable.add(JsonNull)
        }
        if (remaining.isEmpty()) {
            val existingChild = mutable.getOrNull(index)
            val nextChild = existingChild?.let { mergeValue(it, value) } ?: value
            if (index == mutable.size) {
                mutable.add(nextChild)
            } else {
                mutable[index] = nextChild
            }
        } else {
            val existingChild = mutable.getOrNull(index) ?: containerFor(remaining.first().value)
            val nextChild = setAtPath(existingChild, remaining, value)
            if (index == mutable.size) {
                mutable.add(nextChild)
            } else {
                mutable[index] = nextChild
            }
        }
        return JsonArray(mutable)
    }

    private fun removeAtPath(
        current: JsonElement,
        segments: List<JsonPointerSegment>,
    ): JsonElement {
        if (segments.isEmpty()) return EmptyJsonObject
        val segment = segments.first().value
        val remaining = segments.drop(1)
        return when (current) {
            is JsonObject -> {
                if (remaining.isEmpty()) {
                    JsonObject(current - segment)
                } else {
                    val child = current[segment] ?: return current
                    JsonObject(current + (segment to removeAtPath(child, remaining)))
                }
            }
            is JsonArray -> {
                val index = segment.toIntOrNull()?.takeIf { it in current.indices } ?: return current
                val mutable = current.toMutableList()
                if (remaining.isEmpty()) {
                    mutable.removeAt(index)
                } else {
                    mutable[index] = removeAtPath(mutable[index], remaining)
                }
                JsonArray(mutable)
            }
            else -> current
        }
    }

    private fun mergeValue(
        current: JsonElement,
        incoming: JsonElement,
    ): JsonElement =
        if (current is JsonObject && incoming is JsonObject) {
            deepMergeObjects(current, incoming)
        } else {
            incoming
        }

    private fun deepMergeObjects(
        current: JsonObject,
        incoming: JsonObject,
    ): JsonObject {
        val merged = current.toMutableMap()
        incoming.forEach { (key, incomingValue) ->
            val currentValue = merged[key]
            merged[key] = if (currentValue is JsonObject && incomingValue is JsonObject) {
                deepMergeObjects(currentValue, incomingValue)
            } else {
                incomingValue
            }
        }
        return JsonObject(merged)
    }

    private fun containerFor(segment: String): JsonElement =
        if (segment == "-" || segment.toIntOrNull() != null) JsonArray(emptyList()) else EmptyJsonObject
}

private data class JsonPointerSegment(val value: String)

internal val EmptyJsonObject = JsonObject(emptyMap())
