package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

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
    fun all(backend: HostCanvasBackend): List<HostExternalTool> = listOf(
        HostCanvasTool(CanvasToolContract.create, "Failed to create canvas") { caller, input -> create(backend, caller, input) },
        HostCanvasTool(CanvasToolContract.getScene, "Failed to get scene") { caller, input ->
            withCanvas(backend, caller, input) { entry ->
                val scene = backend.scene(entry)
                success(CanvasGetSceneResult(sceneJson = scene.sceneJson, revision = scene.revision))
            }
        },
        HostCanvasTool(CanvasToolContract.replaceScene, "Failed to replace scene") { caller, input ->
            val sceneJson = input.string("scene_json") ?: return@HostCanvasTool missing("scene_json")
            withCanvas(backend, caller, input) { entry ->
                val replace = CanvasOp.ReplaceSceneOp(opId = "", actorId = caller.agentId, lamport = 0L, sceneJson = sceneJson)
                published(backend.publish(caller, entry, listOf(replace))) { CanvasReplaceSceneResult(ok = true, revision = it) }
            }
        },
        HostCanvasTool(CanvasToolContract.applyOps, "Failed to apply ops") { caller, input ->
            val opsJson = input["ops"] ?: return@HostCanvasTool missing("ops")
            val ops = hostCanvasJson.decodeFromJsonElement<List<CanvasOp>>(opsJson)
            withCanvas(backend, caller, input) { entry ->
                published(backend.publish(caller, entry, ops)) { CanvasApplyOpsResult(ok = true, revision = it) }
            }
        },
        HostCanvasTool(CanvasToolContract.list, "Failed to list canvases") { caller, input -> list(backend, caller, input) },
    )

    private suspend fun create(backend: HostCanvasBackend, caller: HostCanvasCaller, input: JsonObject): ExternalToolResult {
        val conversationId = input.string("conversation_id")
        val title = input.string("title") ?: "Untitled Canvas"
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

    private suspend fun list(backend: HostCanvasBackend, caller: HostCanvasCaller, input: JsonObject): ExternalToolResult {
        val conversationId = input.string("conversation_id")
        val ids = if (conversationId == null) {
            backend.list(caller).map { it.canvasId }
        } else {
            (backend.conversation(caller, conversationId, claim = false) as? HostCanvasAccess.Granted)
                ?.let { listOf(it.entry.canvasId) }
                .orEmpty()
        }
        return success(CanvasListResult(ids = ids))
    }

    private suspend fun withCanvas(
        backend: HostCanvasBackend,
        caller: HostCanvasCaller,
        input: JsonObject,
        action: suspend (HostCanvasEntry) -> ExternalToolResult,
    ): ExternalToolResult {
        val canvasId = input.string("canvas_id") ?: return missing("canvas_id")
        return when (val access = backend.open(caller, canvasId)) {
            is HostCanvasAccess.Granted -> action(access.entry)
            is HostCanvasAccess.Denied -> ExternalToolResult.Error(access.reason)
        }
    }

    private inline fun <reified T> published(outcome: HostCanvasPublish, result: (Long) -> T): ExternalToolResult =
        when (outcome) {
            is HostCanvasPublish.Published -> success(result(outcome.revision))
            is HostCanvasPublish.Denied -> ExternalToolResult.Error(outcome.reason)
        }

    private inline fun <reified T> success(value: T): ExternalToolResult =
        ExternalToolResult.Success(hostCanvasJson.encodeToString(kotlinx.serialization.serializer<T>(), value))

    private fun missing(parameter: String) = ExternalToolResult.Error("Missing required parameter: $parameter")

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
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
