package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val hostCanvasJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * The `canvas.*` tools run by the host (letta-mobile-aknkw.3), for every agent runtime the host
 * serves: registered through `runtime_start.external_tools` like any tool, answered from the relay's
 * log by [HostCanvasBackend]. Same names, descriptions and inputs as the apps' own
 * [CanvasExternalTools] ([CanvasToolContract]).
 *
 * The caller is the runtime scope the App Server stamped on the call. A call without an agent is
 * refused before anything is read, and nothing in the input can name a different caller.
 */
object HostCanvasTools {
    fun all(backend: HostCanvasBackend, renderer: CanvasPreviewRenderer? = null): List<HostExternalTool> = listOf(
        HostCanvasTool(CanvasToolContract.create, "Failed to create canvas") { caller, input -> create(backend, caller, input) },
        HostCanvasTool(CanvasToolContract.getScene, "Failed to get scene") { caller, input ->
            withCanvas(backend, caller, input) { entry -> getScene(backend, entry) }
        },
        HostCanvasTool(CanvasToolContract.replaceScene, "Failed to replace scene") { caller, input ->
            val sceneJson = HostCanvasToolInputs.sceneJson(input) ?: return@HostCanvasTool missing("scene_json")
            withCanvas(backend, caller, input) { entry ->
                val replace = CanvasOp.ReplaceSceneOp(opId = "", actorId = caller.agentId, lamport = 0L, sceneJson = sceneJson)
                if (CanvasDryRun.requested(input)) return@withCanvas dryRun(backend, caller, entry, listOf(replace))
                published(backend.publish(caller, entry, listOf(replace))) { CanvasReplaceSceneResult(ok = true, revision = it, canvasId = entry.canvasId) }
            }
        },
        HostCanvasTool(CanvasToolContract.applyOps, "Failed to apply ops") { caller, input ->
            val opsJson = input["ops"] ?: return@HostCanvasTool missing("ops")
            val ops = HostCanvasToolInputs.ops(opsJson)
            withCanvas(backend, caller, input) { entry ->
                if (CanvasDryRun.requested(input)) return@withCanvas dryRun(backend, caller, entry, ops)
                published(backend.publish(caller, entry, ops)) { CanvasApplyOpsResult(ok = true, revision = it, canvasId = entry.canvasId) }
            }
        },
        HostCanvasTool(CanvasToolContract.list, "Failed to list canvases") { caller, input -> list(backend, caller, input) },
    ) + listOfNotNull(renderer?.let { previewRenderer ->
        HostCanvasTool(CanvasToolContract.renderPreview, "Failed to render preview") { caller, input ->
            withCanvas(backend, caller, input) { entry -> preview(backend, previewRenderer, caller, entry, input) }
        }
    })

    private suspend fun preview(
        backend: HostCanvasBackend,
        renderer: CanvasPreviewRenderer,
        caller: HostCanvasCaller,
        entry: HostCanvasEntry,
        input: JsonObject,
    ): ExternalToolResult {
        val viewport = CanvasPreviewViewport.parse(input)
        val sceneInput = HostCanvasToolInputs.sceneJson(input)
        val opsInput = input["ops"]
        require(sceneInput == null || opsInput == null) { "Specify scene_json or ops, not both" }
        val candidate = sceneInput != null || opsInput != null
        val scene = if (candidate) {
            val ops = if (sceneInput != null) {
                listOf(CanvasOp.ReplaceSceneOp(opId = "", actorId = caller.agentId, lamport = 0L, sceneJson = sceneInput))
            } else {
                HostCanvasToolInputs.ops(requireNotNull(opsInput))
            }
            when (val check = backend.check(caller, entry, ops)) {
                is HostCanvasCheck.Denied -> return ExternalToolResult.Error(check.reason)
                is HostCanvasCheck.Checked -> when (val result = check.result) {
                    is CanvasBatchCheck.Invalid -> return ExternalToolResult.Error(result.message)
                    is CanvasBatchCheck.Valid -> HostCanvasScene(result.sceneJson, check.revision, 0L)
                }
            }
        } else {
            backend.scene(entry)
        }
        return success(CanvasPreviewResult(entry.canvasId, scene.revision, candidate, viewport,
            renderer.render(scene.sceneJson, viewport)))
    }

    private suspend fun getScene(backend: HostCanvasBackend, entry: HostCanvasEntry): ExternalToolResult {
        val scene = backend.scene(entry)
        return success(
            CanvasGetSceneResult(
                sceneJson = scene.sceneJson,
                revision = scene.revision,
                canvasId = entry.canvasId,
                schemaHint = CanvasSceneSchema.hint,
            ),
        )
    }

    private suspend fun create(backend: HostCanvasBackend, caller: HostCanvasCaller, input: JsonObject): ExternalToolResult {
        val conversationId = HostCanvasToolInputs.string(input, "conversation_id")
        val title = HostCanvasToolInputs.string(input, "title") ?: "Untitled Canvas"
        val entry = if (conversationId == null) {
            backend.create(caller, title)
        } else {
            when (val access = backend.conversation(caller, conversationId, claim = true)) {
                is HostCanvasAccess.Granted -> access.entry
                is HostCanvasAccess.Denied -> return ExternalToolResult.Error(access.reason)
                null -> return ExternalToolResult.Error("Canvas not found for conversation '$conversationId'")
            }
        }
        return success(CanvasCreateResult(canvasId = entry.canvasId))
    }

    /**
     * The canvases [caller] may read, its own conversation's first and marked `current`: an agent
     * picking the first entry used to draw on another conversation's board (letta-mobile-qygvv.21).
     */
    private suspend fun list(backend: HostCanvasBackend, caller: HostCanvasCaller, input: JsonObject): ExternalToolResult {
        val conversationId = HostCanvasToolInputs.string(input, "conversation_id")
        val entries = if (conversationId == null) {
            backend.list(caller)
        } else {
            listOfNotNull((backend.conversation(caller, conversationId, claim = false) as? HostCanvasAccess.Granted)?.entry)
        }
        val listed = entries.map { it.listed(current = it.conversationId != null && it.conversationId == caller.conversationId) }
            .sortedByDescending { it.current }
        return success(CanvasListResult(ids = listed.map { it.canvasId }, canvases = listed))
    }

    private fun HostCanvasEntry.listed(current: Boolean) = CanvasListEntry(canvasId, title, conversationId, current)

    /**
     * Runs [action] on the canvas the call names, or, naming none, on the canvas of the
     * conversation the call came from (created on first use).
     */
    private suspend fun withCanvas(
        backend: HostCanvasBackend,
        caller: HostCanvasCaller,
        input: JsonObject,
        action: suspend (HostCanvasEntry) -> ExternalToolResult,
    ): ExternalToolResult {
        val canvasId = HostCanvasToolInputs.string(input, "canvas_id")?.takeIf { it.isNotBlank() }
        val access = if (canvasId != null) backend.open(caller, canvasId) else backend.ownConversation(caller)
        return when (access) {
            is HostCanvasAccess.Granted -> action(access.entry)
            is HostCanvasAccess.Denied -> ExternalToolResult.Error(access.reason)
            null -> ExternalToolResult.Error(NO_DEFAULT_CANVAS)
        }
    }

    /** A `dry_run` call: the batch checked against the canvas as it is now, and nothing published. */
    private suspend fun dryRun(
        backend: HostCanvasBackend,
        caller: HostCanvasCaller,
        entry: HostCanvasEntry,
        ops: List<CanvasOp>,
    ): ExternalToolResult = when (val checked = backend.check(caller, entry, ops)) {
        is HostCanvasCheck.Denied -> ExternalToolResult.Error(checked.reason)
        is HostCanvasCheck.Checked -> success(CanvasDryRun.result(checked.result, checked.revision, entry.canvasId))
    }

    private inline fun <reified T> published(outcome: HostCanvasPublish, result: (Long) -> T): ExternalToolResult =
        when (outcome) {
            is HostCanvasPublish.Published -> success(result(outcome.revision))
            is HostCanvasPublish.Denied -> ExternalToolResult.Error(outcome.reason)
            is HostCanvasPublish.Invalid -> ExternalToolResult.Error(outcome.reason)
        }

    private inline fun <reified T> success(value: T): ExternalToolResult =
        ExternalToolResult.Success(hostCanvasJson.encodeToString(kotlinx.serialization.serializer<T>(), value))

    private fun missing(parameter: String) = ExternalToolResult.Error("Missing required parameter: $parameter")

    private const val NO_DEFAULT_CANVAS =
        "Missing required parameter: canvas_id (this call is not in a conversation, so there is no default canvas; use canvas.list or canvas.create)"
}

/** One host canvas tool: [definition] for the model, [run] for a call with a known caller. */
private class HostCanvasTool(
    private val definition: CanvasToolDefinition,
    private val failurePrefix: String,
    private val run: suspend (HostCanvasCaller, JsonObject) -> ExternalToolResult,
) : HostExternalTool {
    override val name: String get() = definition.name
    override val description: String get() = definition.description
    override val inputSchema: JsonObject get() = definition.inputSchema
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        invoke(input, ExternalToolCaller(agentId))

    override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
        val agentId = caller.agentId
        if (agentId.isNullOrBlank()) {
            return ExternalToolResult.Error("$failurePrefix: canvas tools require an authenticated agent identity")
        }
        return try {
            run(HostCanvasCaller(agentId, caller.conversationId), input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ExternalToolResult.Error("$failurePrefix: ${e.message}")
        }
    }
}
