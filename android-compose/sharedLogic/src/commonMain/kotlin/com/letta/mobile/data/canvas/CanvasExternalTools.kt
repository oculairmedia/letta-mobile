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
    callerId: String? = null,
): CanvasLookupResult {
    val canvasIdStr = input["canvas_id"]?.jsonPrimitive?.contentOrNull
        ?: return CanvasLookupResult.Error(ExternalToolResult.Error("Missing required parameter: canvas_id"))
    val canvasId = CanvasId(canvasIdStr)
    val activeSession = sessions.get(canvasId)
    val doc = activeSession?.document?.value ?: store.get(canvasId)
        ?: return CanvasLookupResult.Error(ExternalToolResult.Error("Canvas not found: $canvasIdStr"))
    if (doc.acl != null && !doc.acl.canRead(callerId)) {
        return CanvasLookupResult.Error(ExternalToolResult.Error("Unauthorized: actor '$callerId' cannot read canvas '$canvasIdStr'"))
    }
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

    val defaultAcl = CanvasAcl(
        ownerUserId = "local_user",
        writerAgentIds = if (callerAgentId != null) setOf(callerAgentId) else emptySet(),
    )

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
            acl = defaultAcl,
            updatedAtEpochMs = now,
        )
    )
    return newId
}

private suspend fun executeReplaceScene(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry,
    input: JsonObject,
    agentId: String?,
): ExternalToolResult {
    val canvasIdStr = input["canvas_id"]?.jsonPrimitive?.contentOrNull
        ?: return ExternalToolResult.Error("Missing required parameter: canvas_id")
    val sceneJson = input["scene_json"]?.jsonPrimitive?.contentOrNull
        ?: return ExternalToolResult.Error("Missing required parameter: scene_json")
    val canvasId = CanvasId(canvasIdStr)
    val callerId = agentId ?: input["agent_id"]?.jsonPrimitive?.contentOrNull
    val activeSession = sessions.get(canvasId)
    val doc = activeSession?.document?.value ?: store.get(canvasId)
        ?: return ExternalToolResult.Error("Canvas not found: $canvasIdStr")

    if (doc.acl != null && !doc.acl.canWrite(callerId)) {
        return ExternalToolResult.Error("Unauthorized: actor '$callerId' cannot write to canvas '$canvasIdStr'")
    }

    val revision = if (activeSession != null) {
        activeSession.applyAgentReplace(sceneJson, actorId = callerId).revision
    } else {
        val updated = doc.copy(
            revision = doc.revision + 1L,
            sceneJson = sceneJson,
            updatedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        store.upsert(updated)
        updated.revision
    }
    return ExternalToolResult.Success(
        canvasJson.encodeToString(CanvasReplaceSceneResult(ok = true, revision = revision))
    )
}

private suspend fun executeApplyOps(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry,
    input: JsonObject,
    agentId: String?,
): ExternalToolResult {
    val canvasIdStr = input["canvas_id"]?.jsonPrimitive?.contentOrNull
        ?: return ExternalToolResult.Error("Missing required parameter: canvas_id")
    val opsJson = input["ops"] ?: return ExternalToolResult.Error("Missing required parameter: ops")
    val ops = canvasJson.decodeFromJsonElement<List<CanvasOp>>(opsJson)
    val canvasId = CanvasId(canvasIdStr)
    val callerId = agentId ?: input["agent_id"]?.jsonPrimitive?.contentOrNull
    val activeSession = sessions.get(canvasId)
    val doc = activeSession?.document?.value ?: store.get(canvasId)
        ?: return ExternalToolResult.Error("Canvas not found: $canvasIdStr")

    if (doc.acl != null && !doc.acl.canWrite(callerId)) {
        return ExternalToolResult.Error("Unauthorized: actor '$callerId' cannot write to canvas '$canvasIdStr'")
    }
    for (op in ops) {
        if (doc.acl != null && !doc.acl.canWrite(op.actorId)) {
            return ExternalToolResult.Error("Unauthorized: op actor '${op.actorId}' cannot write to canvas '$canvasIdStr'")
        }
    }

    val revision = if (activeSession != null) {
        activeSession.applyOps(ops).revision
    } else {
        val projected = CanvasOpProjector.project(doc.sceneJson, ops)
        val updated = doc.copy(
            revision = doc.revision + 1L,
            sceneJson = projected,
            updatedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        store.upsert(updated)
        updated.revision
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

/**
 * Tool: canvas.create
 * Creates a new canvas document or resolves an existing conversation canvas.
 */
class CanvasCreateTool(
    private val store: CanvasDocumentStore,
    private val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : HostExternalTool {
    override val name: String = NAME
    override val description: String =
        "Create a new canvas document. Returns the created canvas_id."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("conversation_id") { put("type", "string") }
            putJsonObject("agent_id") { put("type", "string") }
        }
        put("additionalProperties", false)
    }
    override val capability: Capability = Capability.ImageHydration

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
    private val store: CanvasDocumentStore,
    private val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : HostExternalTool {
    override val name: String = NAME
    override val description: String =
        "Get the current DrawBox scene JSON and revision for a canvas."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("canvas_id") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("canvas_id")) })
        put("additionalProperties", false)
    }
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        val callerId = agentId ?: input["agent_id"]?.jsonPrimitive?.contentOrNull
        when (val lookup = findCanvasDocument(input, store, sessions, callerId)) {
            is CanvasLookupResult.Error -> lookup.result
            is CanvasLookupResult.Found -> ExternalToolResult.Success(
                canvasJson.encodeToString(
                    CanvasGetSceneResult(
                        sceneJson = lookup.doc.sceneJson,
                        revision = lookup.doc.revision,
                    )
                )
            )
        }
    }.getOrElse { ExternalToolResult.Error("Failed to get scene: ${it.message}") }

    companion object {
        const val NAME = "canvas.get_scene"
    }
}

/**
 * Tool: canvas.replace_scene
 * Replaces the DrawBox scene JSON for a canvas, incrementing revision.
 */
class CanvasReplaceSceneTool(
    private val store: CanvasDocumentStore,
    private val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : HostExternalTool {
    override val name: String = NAME
    override val description: String =
        "Replace the DrawBox scene JSON for a canvas, incrementing its revision."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("canvas_id") { put("type", "string") }
            putJsonObject("scene_json") { put("type", "string") }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("canvas_id"))
            add(JsonPrimitive("scene_json"))
        })
        put("additionalProperties", false)
    }
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        executeReplaceScene(store, sessions, input, agentId)
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
    private val store: CanvasDocumentStore,
    private val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : HostExternalTool {
    override val name: String = NAME
    override val description: String =
        "Apply a sequence of Canvas operations to the canvas."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("canvas_id") { put("type", "string") }
            putJsonObject("ops") { put("type", "array") }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("canvas_id"))
            add(JsonPrimitive("ops"))
        })
        put("additionalProperties", false)
    }
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        executeApplyOps(store, sessions, input, agentId)
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
    private val store: CanvasDocumentStore,
    private val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : HostExternalTool {
    override val name: String = NAME
    override val description: String =
        "Export the SVG representation of a canvas."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("canvas_id") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("canvas_id")) })
        put("additionalProperties", false)
    }
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = runCatching {
        val callerId = agentId ?: input["agent_id"]?.jsonPrimitive?.contentOrNull
        when (val lookup = findCanvasDocument(input, store, sessions, callerId)) {
            is CanvasLookupResult.Error -> lookup.result
            is CanvasLookupResult.Found -> {
                val svgContent = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 600\"></svg>"
                ExternalToolResult.Success(
                    canvasJson.encodeToString(CanvasExportSvgResult(svg = svgContent))
                )
            }
        }
    }.getOrElse { ExternalToolResult.Error("Failed to export SVG: ${it.message}") }

    companion object {
        const val NAME = "canvas.export_svg"
    }
}

/**
 * Tool: canvas.list
 * Lists canvas IDs by conversation or agent.
 */
class CanvasListTool(
    private val store: CanvasDocumentStore,
    private val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : HostExternalTool {
    override val name: String = NAME
    override val description: String =
        "List canvas IDs by conversation or agent."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("conversation_id") { put("type", "string") }
            putJsonObject("agent_id") { put("type", "string") }
        }
        put("additionalProperties", false)
    }
    override val capability: Capability = Capability.ImageHydration

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
    /**
     * [sessions] must be the same registry the canvas UI registers into, or every tool falls back
     * to the store and an agent's edits never reach the session the user is looking at.
     */
    fun all(store: CanvasDocumentStore, sessions: CanvasSessionRegistry): List<HostExternalTool> = listOf(
        CanvasCreateTool(store, sessions),
        CanvasGetSceneTool(store, sessions),
        CanvasReplaceSceneTool(store, sessions),
        CanvasApplyOpsTool(store, sessions),
        CanvasExportSvgTool(store, sessions),
        CanvasListTool(store, sessions),
    )
}
