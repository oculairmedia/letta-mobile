package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * `dry_run` for an app's own canvas tools ([CanvasExternalTools]), which share the host's contract
 * ([CanvasToolContract]): a call that only asked to check must never write, whichever runtime
 * answers it (letta-mobile-qygvv.30).
 */
internal object CanvasAppDryRun {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The ops a `canvas.apply_ops` [input] would write, or null when it has none to read. */
    fun applyOps(input: JsonObject): List<CanvasOp>? =
        input["ops"]?.let { runCatching { json.decodeFromJsonElement<List<CanvasOp>>(it) }.getOrNull() }

    /** The replace a `canvas.replace_scene` [input] would write, or null when it names no scene. */
    fun replaceScene(input: JsonObject): List<CanvasOp>? =
        (input["scene_json"] as? JsonPrimitive)?.contentOrNull?.let { listOf(CanvasOp.ReplaceSceneOp("", "", 0L, it)) }

    /** [ops] checked against [doc] as [callerId] would write them; nothing is written. */
    fun answer(doc: CanvasDocument, ops: List<CanvasOp>, callerId: String): ExternalToolResult {
        val check = CanvasBatchValidator.check(doc.sceneJson, ops.map { it.withActor(callerId) })
        val result = CanvasDryRun.result(check, doc.revision, doc.id.value)
        return ExternalToolResult.Success(json.encodeToString(CanvasDryRunResult.serializer(), result))
    }
}
