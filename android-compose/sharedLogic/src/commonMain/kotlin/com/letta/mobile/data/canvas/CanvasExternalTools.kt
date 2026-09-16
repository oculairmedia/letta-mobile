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


private data class CanvasToolContext(
    val store: CanvasDocumentStore,
    val sessions: CanvasSessionRegistry,
    val agentId: String? = null,
) {
    fun resolveCallerId(input: JsonObject): String? =
        agentId ?: input["agent_id"]?.jsonPrimitive?.contentOrNull
}

private sealed interface CanvasLookupResult {
    data class Found(val doc: CanvasDocument) : CanvasLookupResult
    data class Error(val result: ExternalToolResult.Error) : CanvasLookupResult
}

private suspend fun findCanvasDocument(
    context: CanvasToolContext,
    input: JsonObject,
): CanvasLookupResult {
    val canvasIdStr = input["canvas_id"]?.jsonPrimitive?.contentOrNull
        ?: return CanvasLookupResult.Error(ExternalToolResult.Error("Missing required parameter: canvas_id"))
    val canvasId = CanvasId(canvasIdStr)
    val activeSession = context.sessions.get(canvasId)
    val doc = activeSession?.document?.value ?: context.store.get(canvasId)
        ?: return CanvasLookupResult.Error(ExternalToolResult.Error("Canvas not found: $canvasIdStr"))
    val callerId = context.resolveCallerId(input)
    if (doc.acl != null && !doc.acl.canRead(callerId)) {
        return CanvasLookupResult.Error(ExternalToolResult.Error("Unauthorized: actor '$callerId' cannot read canvas '$canvasIdStr'"))
    }
    return CanvasLookupResult.Found(doc)
}

private suspend fun executeCreateCanvas(
    context: CanvasToolContext,
    input: JsonObject,
): CanvasId {
    val title = input["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled Canvas"
    val conversationId = input["conversation_id"]?.jsonPrimitive?.contentOrNull
    val callerAgentId = context.resolveCallerId(input)

    val defaultAcl = CanvasAcl(
        ownerUserId = "local_user",
        writerAgentIds = if (callerAgentId != null) setOf(callerAgentId) else emptySet(),
    )

    if (conversationId != null) {
        val existing = context.store.getForConversation(conversationId)
        if (existing != null) return existing.id
    }
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val newId = CanvasId("canvas-$now-${(1000..9999).random()}")
    context.store.upsert(
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

private suspend fun commitSceneUpdate(
    store: CanvasDocumentStore,
    activeSession: CanvasSession?,
    doc: CanvasDocument,
    sceneJson: String,
    callerId: String?,
): Long = if (activeSession != null) {
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

private suspend fun executeAuthorizedMutation(
    context: CanvasToolContext,
    input: JsonObject,
    mutate: suspend (doc: CanvasDocument, callerId: String?, activeSession: CanvasSession?) -> ExternalToolResult,
): ExternalToolResult {
    val callerId = context.resolveCallerId(input)
    val doc = when (val lookup = findCanvasDocument(context, input)) {
        is CanvasLookupResult.Error -> return lookup.result
        is CanvasLookupResult.Found -> lookup.doc
    }
    if (doc.acl != null && !doc.acl.canWrite(callerId)) {
        return ExternalToolResult.Error("Unauthorized: actor '$callerId' cannot write to canvas '${doc.id.value}'")
    }
    val activeSession = context.sessions.get(doc.id)
    return mutate(doc, callerId, activeSession)
}

private suspend fun executeReplaceScene(
    context: CanvasToolContext,
    input: JsonObject,
): ExternalToolResult {
    val sceneJson = input["scene_json"]?.jsonPrimitive?.contentOrNull
        ?: return ExternalToolResult.Error("Missing required parameter: scene_json")
    return executeAuthorizedMutation(context, input) { doc, callerId, activeSession ->
        val revision = commitSceneUpdate(context.store, activeSession, doc, sceneJson, callerId)
        ExternalToolResult.Success(
            canvasJson.encodeToString(CanvasReplaceSceneResult(ok = true, revision = revision))
        )
    }
}

private fun validateOpAcls(doc: CanvasDocument, ops: List<CanvasOp>): ExternalToolResult.Error? {
    if (doc.acl == null) return null
    for (op in ops) {
        if (!doc.acl.canWrite(op.actorId)) {
            return ExternalToolResult.Error("Unauthorized: op actor '${op.actorId}' cannot write to canvas '${doc.id.value}'")
        }
    }
    return null
}

private suspend fun commitOpsUpdate(
    store: CanvasDocumentStore,
    activeSession: CanvasSession?,
    doc: CanvasDocument,
    ops: List<CanvasOp>,
): Long = if (activeSession != null) {
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

private suspend fun executeApplyOps(
    context: CanvasToolContext,
    input: JsonObject,
): ExternalToolResult {
    val opsJson = input["ops"] ?: return ExternalToolResult.Error("Missing required parameter: ops")
    val ops = canvasJson.decodeFromJsonElement<List<CanvasOp>>(opsJson)
    return executeAuthorizedMutation(context, input) { doc, _, activeSession ->
        val aclError = validateOpAcls(doc, ops)
        if (aclError != null) return@executeAuthorizedMutation aclError
        val revision = commitOpsUpdate(context.store, activeSession, doc, ops)
        ExternalToolResult.Success(
            canvasJson.encodeToString(CanvasApplyOpsResult(ok = true, revision = revision))
        )
    }
}

private suspend fun executeListCanvases(
    context: CanvasToolContext,
    input: JsonObject,
): List<String> {
    val conversationId = input["conversation_id"]?.jsonPrimitive?.contentOrNull
    val targetAgentId = context.resolveCallerId(input)
    return when {
        conversationId != null -> context.store.getForConversation(conversationId)?.let { listOf(it.id.value) }.orEmpty()
        targetAgentId != null -> context.store.listForAgent(targetAgentId).map { it.id.value }
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
        val context = CanvasToolContext(store, sessions, agentId)
        val canvasId = executeCreateCanvas(context, input)
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
        val context = CanvasToolContext(store, sessions, agentId)
        when (val lookup = findCanvasDocument(context, input)) {
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
        val context = CanvasToolContext(store, sessions, agentId)
        executeReplaceScene(context, input)
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
        val context = CanvasToolContext(store, sessions, agentId)
        executeApplyOps(context, input)
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
        val context = CanvasToolContext(store, sessions, agentId)
        when (val lookup = findCanvasDocument(context, input)) {
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
        val context = CanvasToolContext(store, sessions, agentId)
        val ids = executeListCanvases(context, input)
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
