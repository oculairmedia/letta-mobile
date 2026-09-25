package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The words in a block document (the Cascade editor's JSON), without their formatting: one line
 * per block, in order, nested blocks included. Used where a document's text has to become plain
 * text - a shape label folded into the shape it labelled, for one.
 *
 * Reads only what it needs (`blocks`, each block's `content.text`, and `children`), so a newer
 * document with fields it does not know still yields its text. Unreadable input gives "".
 */
object CanvasDocumentText {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A recognizable Cascade document has the `blocks` array the renderer traverses. The array may
     * be empty: that is how a newly created note is represented, unlike an unrelated JSON format.
     */
    fun isRecognizedDocument(documentJson: String): Boolean {
        val root = runCatching { json.parseToJsonElement(documentJson) }.getOrNull() as? JsonObject ?: return false
        return root["blocks"] is JsonArray
    }

    fun plainText(documentJson: String): String {
        if (documentJson.isBlank()) return ""
        val root = runCatching { json.parseToJsonElement(documentJson) }.getOrNull() as? JsonObject ?: return ""
        val lines = mutableListOf<String>()
        collect(root["blocks"], lines)
        return lines.joinToString("\n").trim()
    }

    private fun collect(blocks: JsonElement?, into: MutableList<String>) {
        (blocks as? JsonArray)?.forEach { block ->
            val obj = block as? JsonObject ?: return@forEach
            val content = obj["content"] as? JsonObject
            (content?.get("text") as? JsonPrimitive)?.contentOrNull?.let(into::add)
            collect(obj["children"], into)
        }
    }
}
