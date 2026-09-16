package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val canvasJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}


private sealed interface CanvasLookupResult {
    data class Found(val doc: CanvasDocument) : CanvasLookupResult
    data class Error(val result: ExternalToolResult.Error) : CanvasLookupResult
}

private suspend fun findCanvasDocument(
    input: JsonObject,
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry,
): CanvasLookupResult {
    val canvasIdStr = input["canvas_id"]?.jsonPrimitive?.contentOrNull
        ?: return CanvasLookupResult.Error(ExternalToolResult.Error("Missing required parameter: canvas_id"))
    val canvasId = CanvasId(canvasIdStr)
    val activeSession = sessions.get(canvasId)
    val doc = activeSession?.document?.value ?: store.get(canvasId)
        ?: return CanvasLookupResult.Error(ExternalToolResult.Error("Canvas not found: $canvasIdStr"))
    return CanvasLookupResult.Found(doc)
}

private suspend fun executeCreateCanvas(
    store: CanvasDocumentStore,
    input: JsonObject,
    agentId: String?,
): CanvasId {
    val title = input["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled Canvas"
    val conversationId = input["conversation_id"]?.jsonPrimitive?.contentOrNull
    val callerAgentId = input["agent_id"]?.jsonPrimitive?.contentOrNull ?: agentId

    if (conversationId != null) {
        val existing = store.getForConversation(conversationId)
        if (existing != null) return existing.id
    }
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val newId = CanvasId("canvas-$now-${(1000..9999).random()}")
    store.upsert(
        CanvasDocument(
            id = newId,
            agentId = callerAgentId,
            conversationId = conversationId,
            title = title,
            revision = 1L,
            sceneJson = "",
            updatedAtEpochMs = now,
        )
    )
    return newId
}

private suspend fun updateDocumentScene(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry,
    canvasId: CanvasId,
    newScene: String,
    onSession: suspend (CanvasSession) -> Long,
): Long {
    val activeSession = sessions.get(canvasId)
    if (activeSession != null) {
        return onSession(activeSession)
    }
    val doc = store.get(canvasId) ?: throw NoSuchElementException("Canvas not found: ${canvasId.value}")
    val updated = doc.copy(
        revision = doc.revision + 1L,
        sceneJson = newScene,
        updatedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    )
    store.upsert(updated)
    return updated.revision
}

private suspend fun executeReplaceScene(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry,
    input: JsonObject,
): ExternalToolResult {
    val canvasIdStr = input["canvas_id"]?.jsonPrimitive?.contentOrNull
        ?: return ExternalToolResult.Error("Missing required parameter: canvas_id")
    val sceneJson = input["scene_json"]?.jsonPrimitive?.contentOrNull
        ?: return ExternalToolResult.Error("Missing required parameter: scene_json")
    val canvasId = CanvasId(canvasIdStr)
    val revision = updateDocumentScene(store, sessions, canvasId, sceneJson) { session ->
        session.applyAgentReplace(sceneJson).revision
    }
    return ExternalToolResult.Success(
        canvasJson.encodeToString(CanvasReplaceSceneResult(ok = true, revision = revision))
    )
}

private suspend fun executeApplyOps(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry,
    input: JsonObject,
): ExternalToolResult {
    val canvasIdStr = input["canvas_id"]?.jsonPrimitive?.contentOrNull
        ?: return ExternalToolResult.Error("Missing required parameter: canvas_id")
    val opsJson = input["ops"] ?: return ExternalToolResult.Error("Missing required parameter: ops")
    val ops = canvasJson.decodeFromJsonElement<List<CanvasOp>>(opsJson)
    val canvasId = CanvasId(canvasIdStr)
    val activeSession = sessions.get(canvasId)
    val revision = if (activeSession != null) {
        activeSession.applyOps(ops).revision
    } else {
        val doc = store.get(canvasId) ?: return ExternalToolResult.Error("Canvas not found: $canvasIdStr")
        val projected = CanvasOpProjector.project(doc.sceneJson, ops)
        val updated = doc.copy(
            revision = doc.revision + 1L,
            sceneJson = projected,
            updatedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        store.upsert(updated)
        updated.revision
    }
    }
    return ExternalToolResult.Success(
        canvasJson.encodeToString(CanvasApplyOpsResult(ok = true, revision = revision))
    )
}

private suspend fun executeListCanvases(
    store: CanvasDocumentStore,
    input: JsonObject,
    agentId: String?,
): List<String> {
    val conversationId = input["conversation_id"]?.jsonPrimitive?.contentOrNull
    val targetAgentId = input["agent_id"]?.jsonPrimitive?.contentOrNull ?: agentId
    return when {
        conversationId != null -> store.getForConversation(conversationId)?.let { listOf(it.id.value) }.orEmpty()
        targetAgentId != null -> store.listForAgent(targetAgentId).map { it.id.value }
        else -> emptyList()
    }
}

private fun canvasSchema(
    properties: Map<String, String>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((name, type) in properties) {
            putJsonObject(name) { put("type", type) }
        }
    }
    if (required.isNotEmpty()) {
        put("required", buildJsonArray {
            for (req in required) add(JsonPrimitive(req))
        })
    }
    put("additionalProperties", false)
}

abstract class AbstractCanvasTool(
    final override val name: String,
    final override val description: String,
    final override val inputSchema: JsonObject,
    protected val store: CanvasDocumentStore,
    protected val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : HostExternalTool {
    final override val capability: Capability = Capability.ImageHydration
}

private suspend fun withCanvasDocument(
    input: JsonObject,
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry,
    errorMessagePrefix: String,
    block: (CanvasDocument) -> ExternalToolResult,
): ExternalToolResult = runCatching {
    when (val lookup = findCanvasDocument(input, store, sessions)) {
        is CanvasLookupResult.Error -> lookup.result
        is CanvasLookupResult.Found -> block(lookup.doc)
    }
}.getOrElse { ExternalToolResult.Error("$errorMessagePrefix: ${it.message}") }

/**
 * Tool: canvas.create
 * Creates a new canvas document or resolves an existing conversation canvas.
 */
class CanvasCreateTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : AbstractCanvasTool(
    name = NAME,
    description = "Create a new canvas document. Returns the created canvas_id.",
    inputSchema = canvasSchema(
        mapOf("title" to "string", "conversation_id" to "string", "agent_id" to "string"),
    ),
    store = store,
    sessions = sessions,
) {
    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        val canvasId = executeCreateCanvas(store, input, agentId)
        ExternalToolResult.Success(canvasJson.encodeToString(CanvasCreateResult(canvasId = canvasId.value)))
    }.getOrElse { ExternalToolResult.Error("Failed to create canvas: ${it.message}") }

    companion object {
        const val NAME = "canvas.create"
    }
}

/**
 * Tool: canvas.get_scene
 * Retrieves the current DrawBox scene JSON and revision for a canvas.
 */
class CanvasGetSceneTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : AbstractCanvasTool(
    name = NAME,
    description = "Get the current DrawBox scene JSON and revision for a canvas.",
    inputSchema = canvasSchema(mapOf("canvas_id" to "string"), listOf("canvas_id")),
    store = store,
    sessions = sessions,
) {
    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        withCanvasDocument(input, store, sessions, "Failed to get scene") { doc ->
            ExternalToolResult.Success(
                canvasJson.encodeToString(
                    CanvasGetSceneResult(
                        sceneJson = doc.sceneJson,
                        revision = doc.revision,
                    )
                )
            )
        }

    companion object {
        const val NAME = "canvas.get_scene"
    }
}

/**
 * Tool: canvas.replace_scene
 * Replaces the DrawBox scene JSON for a canvas, incrementing revision.
 */
class CanvasReplaceSceneTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : AbstractCanvasTool(
    name = NAME,
    description = "Replace the DrawBox scene JSON for a canvas, incrementing its revision.",
    inputSchema = canvasSchema(
        mapOf("canvas_id" to "string", "scene_json" to "string"),
        listOf("canvas_id", "scene_json"),
    ),
    store = store,
    sessions = sessions,
) {
    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        executeReplaceScene(store, sessions, input)
    }.getOrElse { ExternalToolResult.Error("Failed to replace scene: ${it.message}") }

    companion object {
        const val NAME = "canvas.replace_scene"
    }
}

/**
 * Tool: canvas.apply_ops
 * Applies a list of Canvas operations to the canvas.
 */
class CanvasApplyOpsTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : AbstractCanvasTool(
    name = NAME,
    description = "Apply a sequence of Canvas operations to the canvas.",
    inputSchema = canvasSchema(
        mapOf("canvas_id" to "string", "ops" to "array"),
        listOf("canvas_id", "ops"),
    ),
    store = store,
    sessions = sessions,
) {
    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        executeApplyOps(store, sessions, input)
    }.getOrElse { ExternalToolResult.Error("Failed to apply ops: ${it.message}") }

    companion object {
        const val NAME = "canvas.apply_ops"
    }
}

/**
 * Tool: canvas.export_svg
 * Returns the SVG export representation of a canvas.
 */
class CanvasExportSvgTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : AbstractCanvasTool(
    name = NAME,
    description = "Export the SVG representation of a canvas.",
    inputSchema = canvasSchema(mapOf("canvas_id" to "string"), listOf("canvas_id")),
    store = store,
    sessions = sessions,
) {
    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        withCanvasDocument(input, store, sessions, "Failed to export SVG") {
            val svgContent = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 600\"></svg>"
            ExternalToolResult.Success(
                canvasJson.encodeToString(CanvasExportSvgResult(svg = svgContent))
            )
        }

    companion object {
        const val NAME = "canvas.export_svg"
    }
}

/**
 * Tool: canvas.list
 * Lists canvas IDs by conversation or agent.
 */
class CanvasListTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : AbstractCanvasTool(
    name = NAME,
    description = "List canvas IDs by conversation or agent.",
    inputSchema = canvasSchema(mapOf("conversation_id" to "string", "agent_id" to "string")),
    store = store,
    sessions = sessions,
) {
    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        val ids = executeListCanvases(store, input, agentId)
        ExternalToolResult.Success(canvasJson.encodeToString(CanvasListResult(ids = ids)))
    }.getOrElse { ExternalToolResult.Error("Failed to list canvases: ${it.message}") }

    companion object {
        const val NAME = "canvas.list"
    }
}

/**
 * Factory for creating all Canvas [HostExternalTool] instances.
 */
object CanvasExternalTools {
    fun all(
        store: CanvasDocumentStore,
        sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
    ): List<HostExternalTool> = listOf(
        CanvasCreateTool(store, sessions),
        CanvasGetSceneTool(store, sessions),
        CanvasReplaceSceneTool(store, sessions),
        CanvasApplyOpsTool(store, sessions),
        CanvasExportSvgTool(store, sessions),
        CanvasListTool(store, sessions),
    )
}
