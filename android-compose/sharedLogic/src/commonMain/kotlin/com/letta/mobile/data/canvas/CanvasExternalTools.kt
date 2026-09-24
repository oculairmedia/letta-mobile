package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

private val canvasJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}


/**
 * [agentId] is the transport-authenticated caller: the runtime scope the App Server stamped on
 * the tool-call frame. It is never read from the tool input, which the model controls, so a
 * caller cannot name a different agent to read or write as it.
 */
private data class CanvasToolContext(
    val store: CanvasDocumentStore,
    val sessions: CanvasSessionRegistry,
    val agentId: String,
) {
    fun resolveCallerId(): String = agentId
}

private fun canRead(doc: CanvasDocument, callerId: String): Boolean =
    doc.acl == null || doc.acl.canRead(callerId)

/** Two tool calls both read revision N and raced to write N+1; one of them lost. */
private fun revisionConflict(doc: CanvasDocument): ExternalToolResult.Error =
    ExternalToolResult.Error(
        "Conflict: canvas '${doc.id.value}' changed since revision ${doc.revision} was read; re-read and retry",
    )

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
    val callerId = context.resolveCallerId()
    if (!canRead(doc, callerId)) {
        return CanvasLookupResult.Error(ExternalToolResult.Error("Unauthorized: actor '$callerId' cannot read canvas '$canvasIdStr'"))
    }
    return CanvasLookupResult.Found(doc)
}

private suspend fun executeCreateCanvas(
    context: CanvasToolContext,
    input: JsonObject,
): ExternalToolResult {
    val title = input["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled Canvas"
    val conversationId = input["conversation_id"]?.jsonPrimitive?.contentOrNull
    val callerAgentId = context.resolveCallerId()

    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val candidate = CanvasDocument(
        id = CanvasId.generate(),
        agentId = callerAgentId,
        conversationId = conversationId,
        title = title,
        revision = 1L,
        sceneJson = "",
        acl = CanvasAcl(
            ownerUserId = CanvasSession.LOCAL_USER_ACTOR_ID,
            writerAgentIds = setOf(callerAgentId),
        ),
        updatedAtEpochMs = now,
    )
    val created = if (conversationId != null) {
        // A conversation has one canvas. Lookup and insert are one store step, so two racing
        // creators share it; and because the conversation id is caller-supplied, an existing
        // canvas is only revealed to a caller its ACL lets read.
        val bound = context.store.createForConversationIfAbsent(candidate)
        if (bound.id != candidate.id && !canRead(bound, callerAgentId)) {
            return ExternalToolResult.Error("Unauthorized: actor '$callerAgentId' cannot read the canvas for conversation '$conversationId'")
        }
        bound
    } else {
        context.store.upsert(candidate)
        candidate
    }
    return ExternalToolResult.Success(canvasJson.encodeToString(CanvasCreateResult(canvasId = created.id.value)))
}

/**
 * Persists a new scene for [doc] with no session holding the write lock. Tool calls dispatch
 * concurrently, so the write is conditional on the revision this call read: a stale writer
 * gets `null` instead of silently overwriting the other call's scene under the same revision.
 */
private suspend fun persistWithoutSession(
    store: CanvasDocumentStore,
    doc: CanvasDocument,
    sceneJson: String,
): Long? {
    val updated = doc.copy(
        revision = doc.revision + 1L,
        sceneJson = sceneJson,
        updatedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    )
    return if (store.upsertIfRevision(updated, expectedRevision = doc.revision)) updated.revision else null
}

private suspend fun commitSceneUpdate(
    store: CanvasDocumentStore,
    activeSession: CanvasSession?,
    doc: CanvasDocument,
    sceneJson: String,
    callerId: String,
): Long? = if (activeSession != null) {
    activeSession.applyAgentReplace(sceneJson, actorId = callerId).revision
} else {
    persistWithoutSession(store, doc, sceneJson)
}

private suspend fun executeAuthorizedMutation(
    context: CanvasToolContext,
    input: JsonObject,
    mutate: suspend (doc: CanvasDocument, callerId: String, activeSession: CanvasSession?) -> ExternalToolResult,
): ExternalToolResult {
    val callerId = context.resolveCallerId()
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
            ?: return@executeAuthorizedMutation revisionConflict(doc)
        ExternalToolResult.Success(
            canvasJson.encodeToString(CanvasReplaceSceneResult(ok = true, revision = revision))
        )
    }
}

private suspend fun commitOpsUpdate(
    store: CanvasDocumentStore,
    activeSession: CanvasSession?,
    doc: CanvasDocument,
    ops: List<CanvasOp>,
): Long? = if (activeSession != null) {
    activeSession.applyOps(ops).revision
} else {
    persistWithoutSession(store, doc, CanvasOpProjector.project(doc.sceneJson, ops))
}

private suspend fun executeApplyOps(
    context: CanvasToolContext,
    input: JsonObject,
): ExternalToolResult {
    val opsJson = input["ops"] ?: return ExternalToolResult.Error("Missing required parameter: ops")
    val suppliedOps = canvasJson.decodeFromJsonElement<List<CanvasOp>>(opsJson)
    return executeAuthorizedMutation(context, input) { doc, callerId, activeSession ->
        // The caller has already passed the write check; every op it sends is its own, whatever
        // actor the input named, so the log, the broadcast and scene provenance all carry it.
        val ops = suppliedOps.map { it.withActor(callerId) }
        val revision = commitOpsUpdate(context.store, activeSession, doc, ops)
            ?: return@executeAuthorizedMutation revisionConflict(doc)
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
    val callerAgentId = context.resolveCallerId()
    return if (conversationId != null) {
        context.store.getForConversation(conversationId)
            ?.takeIf { canRead(it, callerAgentId) }
            ?.let { listOf(it.id.value) }
            .orEmpty()
    } else {
        // agentId and acl are independent fields, so a document can name an agent its ACL
        // has since stopped letting read; listing applies the same check as a direct lookup.
        context.store.listForAgent(callerAgentId).filter { canRead(it, callerAgentId) }.map { it.id.value }
    }
}

/**
 * Base class for Canvas host external tools.
 */
abstract class BaseCanvasTool(
    val store: CanvasDocumentStore,
    val sessions: CanvasSessionRegistry,
) : HostExternalTool {
    override val capability: Capability = Capability.ImageHydration
}

/**
 * Every canvas tool needs to know who is calling before it reads an ACL or stamps ownership on
 * a new document, so a call whose frame carried no runtime agent scope is refused outright.
 */
private inline fun BaseCanvasTool.runWithContext(
    agentId: String?,
    failurePrefix: String,
    action: (CanvasToolContext) -> ExternalToolResult,
): ExternalToolResult {
    if (agentId.isNullOrBlank()) {
        return ExternalToolResult.Error("$failurePrefix: canvas tools require an authenticated agent identity")
    }
    return runCatching {
        action(CanvasToolContext(store, sessions, agentId))
    }.getOrElse { ExternalToolResult.Error("$failurePrefix: ${it.message}") }
}

/**
 * Tool: canvas.create
 * Creates a new canvas document or resolves an existing conversation canvas.
 */
class CanvasCreateTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : BaseCanvasTool(store, sessions) {
    override val name: String = NAME
    override val description: String = CanvasToolContract.create.description
    override val inputSchema: JsonObject = CanvasToolContract.create.inputSchema

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        runWithContext(agentId, "Failed to create canvas") { context ->
            executeCreateCanvas(context, input)
        }

    companion object {
        const val NAME = CanvasToolContract.CREATE
    }
}

/**
 * Tool: canvas.get_scene
 * Retrieves the current DrawBox scene JSON and revision for a canvas.
 */
class CanvasGetSceneTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : BaseCanvasTool(store, sessions) {
    override val name: String = NAME
    override val description: String = CanvasToolContract.getScene.description
    override val inputSchema: JsonObject = CanvasToolContract.getScene.inputSchema

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        runWithContext(agentId, "Failed to get scene") { context ->
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
        }

    companion object {
        const val NAME = CanvasToolContract.GET_SCENE
    }
}

/**
 * Tool: canvas.replace_scene
 * Replaces the DrawBox scene JSON for a canvas, incrementing revision.
 */
class CanvasReplaceSceneTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : BaseCanvasTool(store, sessions) {
    override val name: String = NAME
    override val description: String = CanvasToolContract.replaceScene.description
    override val inputSchema: JsonObject = CanvasToolContract.replaceScene.inputSchema

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        runWithContext(agentId, "Failed to replace scene") { context ->
            executeReplaceScene(context, input)
        }

    companion object {
        const val NAME = CanvasToolContract.REPLACE_SCENE
    }
}

/**
 * Tool: canvas.apply_ops
 * Applies a list of Canvas operations to the canvas.
 */
class CanvasApplyOpsTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : BaseCanvasTool(store, sessions) {
    override val name: String = NAME
    override val description: String = CanvasToolContract.applyOps.description
    override val inputSchema: JsonObject = CanvasToolContract.applyOps.inputSchema

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        runWithContext(agentId, "Failed to apply ops") { context ->
            executeApplyOps(context, input)
        }

    companion object {
        const val NAME = CanvasToolContract.APPLY_OPS
    }
}

/**
 * Tool: canvas.export_svg
 * Returns the SVG export representation of a canvas.
 */
class CanvasExportSvgTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : BaseCanvasTool(store, sessions) {
    override val name: String = NAME
    override val description: String = CanvasToolContract.exportSvg.description
    override val inputSchema: JsonObject = CanvasToolContract.exportSvg.inputSchema

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        runWithContext(agentId, "Failed to export SVG") { context ->
            when (val lookup = findCanvasDocument(context, input)) {
                is CanvasLookupResult.Error -> lookup.result
                is CanvasLookupResult.Found -> ExternalToolResult.Error(CanvasToolContract.EXPORT_SVG_NOT_IMPLEMENTED)
            }
        }

    companion object {
        const val NAME = CanvasToolContract.EXPORT_SVG
    }
}

/**
 * Tool: canvas.list
 * Lists canvas IDs by conversation or agent.
 */
class CanvasListTool(
    store: CanvasDocumentStore,
    sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
) : BaseCanvasTool(store, sessions) {
    override val name: String = NAME
    override val description: String = CanvasToolContract.list.description
    override val inputSchema: JsonObject = CanvasToolContract.list.inputSchema

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        runWithContext(agentId, "Failed to list canvases") { context ->
            val ids = executeListCanvases(context, input)
            ExternalToolResult.Success(canvasJson.encodeToString(CanvasListResult(ids = ids)))
        }

    companion object {
        const val NAME = CanvasToolContract.LIST
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
        CanvasListTool(store, sessions),
    )
}
