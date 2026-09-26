package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.serialization.json.JsonObject

/** Resolves a host-authorized scene before invoking a supplied mobile renderer. */
internal class HostCanvasPreview(
    private val backend: HostCanvasBackend,
    private val renderer: CanvasPreviewRenderer,
) {
    suspend fun run(caller: HostCanvasCaller, entry: HostCanvasEntry, input: JsonObject): ExternalToolResult {
        val viewport = CanvasPreviewViewport.parse(input)
        val proposed = proposal(caller, input)
        val scene = if (proposed == null) backend.scene(entry) else when (val checked = backend.check(caller, entry, proposed)) {
            is HostCanvasCheck.Denied -> return ExternalToolResult.Error(checked.reason)
            is HostCanvasCheck.Checked -> when (val result = checked.result) {
                is CanvasBatchCheck.Invalid -> return ExternalToolResult.Error(result.message)
                is CanvasBatchCheck.Valid -> HostCanvasScene(result.sceneJson, checked.revision, 0L)
            }
        }
        val rendered = renderer.render(scene.sceneJson, viewport)
        return ExternalToolResult.Success(
            kotlinx.serialization.json.Json.encodeToString(
                CanvasPreviewResult(entry.canvasId, scene.revision, proposed != null, viewport, rendered),
            ),
        )
    }

    private fun proposal(caller: HostCanvasCaller, input: JsonObject): List<CanvasOp>? {
        val scene = HostCanvasToolInputs.sceneJson(input)
        val ops = input["ops"]
        require(scene == null || ops == null) { "Specify scene_json or ops, not both" }
        if (scene != null) return listOf(CanvasOp.ReplaceSceneOp("", caller.agentId, 0L, scene))
        return ops?.let(HostCanvasToolInputs::ops)
    }
}
