package com.letta.mobile.data.a2ui

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Stable
data class A2uiSurfaceState(
    val surfaceId: String,
    val catalogId: String? = null,
    val theme: JsonObject? = null,
    val sendDataModel: Boolean = false,
    val agentId: String? = null,
    val conversationId: String? = null,
    val turnId: String? = null,
    val runId: String? = null,
    val rootComponentId: String? = null,
    val components: Map<String, A2uiComponent> = emptyMap(),
    val dataModel: A2uiDataModel = A2uiDataModel(),
) {
    val rootComponent: A2uiComponent?
        get() = rootComponentId?.let(components::get)
}

class A2uiSurfaceManager(
    initialSurfaces: Map<String, A2uiSurfaceState> = emptyMap(),
) {
    private val _surfaces = MutableStateFlow(initialSurfaces)
    val surfaces: StateFlow<Map<String, A2uiSurfaceState>> = _surfaces.asStateFlow()

    fun apply(event: A2uiFrameEvent) {
        _surfaces.update { current ->
            val updated = event.messages.fold(current) { surfaces, message -> surfaces.applyMessage(message) }
            updated.withEnvelopeMetadata(event)
        }
    }

    fun applyMessages(messages: Iterable<A2uiMessage>) {
        _surfaces.update { current ->
            messages.fold(current) { surfaces, message -> surfaces.applyMessage(message) }
        }
    }

    fun applyMessage(message: A2uiMessage) {
        applyMessages(listOf(message))
    }

    fun surface(surfaceId: String): A2uiSurfaceState? = surfaces.value[surfaceId]
}

object A2uiBindingResolver {
    fun resolve(binding: JsonElement?, dataModel: A2uiDataModel): A2uiResolvedBinding = when (binding) {
        null -> A2uiResolvedBinding.Missing
        is JsonPrimitive -> A2uiResolvedBinding.Value(binding)
        is JsonArray -> A2uiResolvedBinding.Value(binding)
        is JsonObject -> resolveObjectBinding(binding, dataModel)
    }

    fun resolve(binding: JsonElement?, dataModel: JsonElement): A2uiResolvedBinding = when (binding) {
        null -> A2uiResolvedBinding.Missing
        is JsonPrimitive -> A2uiResolvedBinding.Value(binding)
        is JsonArray -> A2uiResolvedBinding.Value(binding)
        is JsonObject -> resolveObjectBinding(binding, dataModel)
    }

    fun resolvePath(dataModel: A2uiDataModel, path: String): JsonElement? =
        dataModel.resolve(path)

    fun resolvePath(dataModel: JsonElement, path: String): JsonElement? {
        return A2uiJsonPointer.resolve(dataModel, path)
    }

    fun applyDataModelPatch(
        dataModel: A2uiDataModel,
        path: String,
        value: JsonElement?,
    ): A2uiDataModel = dataModel.apply { applyPatch(path, value) }

    fun applyDataModelPatch(
        dataModel: JsonElement,
        path: String,
        value: JsonElement?,
    ): JsonElement = A2uiJsonPointer.applyPatch(dataModel, path, value)

    fun displayText(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString()
    }

    private fun resolveObjectBinding(
        binding: JsonObject,
        dataModel: A2uiDataModel,
    ): A2uiResolvedBinding {
        binding["path"]?.jsonPrimitiveOrNull?.contentOrNull?.let { path ->
            return dataModel.resolve(path)?.let(A2uiResolvedBinding::Value)
                ?: A2uiResolvedBinding.Missing
        }
        binding["literalString"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["literal"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["value"]?.let { return A2uiResolvedBinding.Value(it) }
        return A2uiResolvedBinding.Missing
    }

    private fun resolveObjectBinding(
        binding: JsonObject,
        dataModel: JsonElement,
    ): A2uiResolvedBinding {
        binding["path"]?.jsonPrimitiveOrNull?.contentOrNull?.let { path ->
            return resolvePath(dataModel, path)?.let(A2uiResolvedBinding::Value)
                ?: A2uiResolvedBinding.Missing
        }
        binding["literalString"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["literal"]?.let { return A2uiResolvedBinding.Value(it) }
        binding["value"]?.let { return A2uiResolvedBinding.Value(it) }
        return A2uiResolvedBinding.Missing
    }
}

sealed interface A2uiResolvedBinding {
    data object Missing : A2uiResolvedBinding
    data class Value(val value: JsonElement) : A2uiResolvedBinding
}

private fun Map<String, A2uiSurfaceState>.applyMessage(message: A2uiMessage): Map<String, A2uiSurfaceState> =
    when (message) {
        is A2uiMessage.CreateSurface -> upsertSurface(message)
        is A2uiMessage.UpdateComponents -> updateComponents(message)
        is A2uiMessage.UpdateDataModel -> updateDataModel(message)
        is A2uiMessage.DeleteSurface -> minus(message.surfaceId)
        is A2uiMessage.Unknown -> this
    }

private fun Map<String, A2uiSurfaceState>.withEnvelopeMetadata(
    event: A2uiFrameEvent,
): Map<String, A2uiSurfaceState> {
    val surfaceIds = event.messages.mapNotNull { it.surfaceId.takeIf(String::isNotBlank) }.toSet()
    if (surfaceIds.isEmpty()) return this
    return mapValues { (surfaceId, surface) ->
        if (surfaceId !in surfaceIds) {
            surface
        } else {
            surface.copy(
                agentId = event.agentId ?: surface.agentId,
                conversationId = event.conversationId ?: surface.conversationId,
                turnId = event.turnId ?: surface.turnId,
                runId = event.runId ?: surface.runId,
            )
        }
    }
}

private fun Map<String, A2uiSurfaceState>.upsertSurface(
    message: A2uiMessage.CreateSurface,
): Map<String, A2uiSurfaceState> {
    val payload = message.createSurface
    val existing = this[payload.surfaceId]
    val next = (existing ?: A2uiSurfaceState(surfaceId = payload.surfaceId)).copy(
        catalogId = payload.catalogId,
        theme = payload.theme,
        sendDataModel = payload.sendDataModel,
    )
    return plus(payload.surfaceId to next)
}

private fun Map<String, A2uiSurfaceState>.updateComponents(
    message: A2uiMessage.UpdateComponents,
): Map<String, A2uiSurfaceState> {
    val payload = message.updateComponents
    val existing = this[payload.surfaceId] ?: A2uiSurfaceState(surfaceId = payload.surfaceId)
    val incoming = payload.components.associateBy(A2uiComponent::id)
    val nextComponents = existing.components + incoming
    val root = payload.root
        ?: existing.rootComponentId
        ?: payload.components.firstOrNull()?.id
    return plus(
        payload.surfaceId to existing.copy(
            rootComponentId = root,
            components = nextComponents,
        )
    )
}

private fun Map<String, A2uiSurfaceState>.updateDataModel(
    message: A2uiMessage.UpdateDataModel,
): Map<String, A2uiSurfaceState> {
    val payload = message.updateDataModel
    val existing = this[payload.surfaceId] ?: A2uiSurfaceState(surfaceId = payload.surfaceId)
    A2uiBindingResolver.applyDataModelPatch(
        dataModel = existing.dataModel,
        path = payload.path,
        value = payload.value,
    )
    return plus(payload.surfaceId to existing)
}

private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
