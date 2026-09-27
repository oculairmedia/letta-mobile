package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.automerge.repo.DocumentId

/** Explicit, one-way import. The caller owns both the legacy canvas and the already-created notebook ID. */
class NotebookCanvasBridge(private val store: NotebookLocalStore) {
    /** Import is atomic within the notebook document; a different source or edited target is a conflict. */
    fun import(canvas: CanvasDocument, target: DocumentId): NotebookCanvasImportResult {
        val scene = canvas.sceneJson.ifBlank { DEFAULT_SCENE }
        val root = requireNotNull(runCatching { Json.parseToJsonElement(scene) }.getOrNull() as? JsonObject) {
            "Canvas scene must be a JSON object"
        }
        require(root["elements"] is JsonArray && root["bgColor"] is JsonPrimitive) {
            "Canvas scene needs elements and bgColor"
        }
        // Embed the exact scene: root metadata, notes, image references and unknown fields survive unchanged.
        val board = buildJsonObject {
            put("schema", "notebook-board/1")
            put("elements", JsonArray(emptyList()))
            put("sceneJson", scene)
        }.toString()
        return store.importCanvasInto(target, canvas.id.value, canvas.title, board)
    }

    /** Return DrawBox scene JSON, not the notebook board envelope. */
    fun drawableScene(target: DocumentId): String? = store.read(target)?.sceneJson?.let { boardJson ->
        val board = Json.parseToJsonElement(boardJson) as JsonObject
        require((board["schema"] as? JsonPrimitive)?.content == "notebook-board/1") { "Unsupported notebook board" }
        (board["sceneJson"] as? JsonPrimitive)?.content ?: JsonObject(
            board.filterKeys { it != "schema" } + ("bgColor" to (board["bgColor"] ?: JsonPrimitive(CanvasSceneSchema.DEFAULT_BG_COLOR))),
        ).toString()
    }

    private companion object {
        const val DEFAULT_SCENE = "{\"bgColor\":\"#ffffffff\",\"elements\":[]}"
    }
}

enum class NotebookCanvasImportResult { IMPORTED, ALREADY_IMPORTED, CONFLICT }
