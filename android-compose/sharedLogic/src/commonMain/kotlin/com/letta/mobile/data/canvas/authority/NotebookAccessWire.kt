package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `notebook.*` admin_rpc result shapes, shared by the host routes and the remote client so the
 * two cannot drift. Every result carries a `status`; anything a reader does not recognize is
 * treated as unavailable, never as content.
 */
object NotebookAccessWire {
    const val RESOLVE = "notebook.reference.resolve"
    const val OPEN = "notebook.note.open"
    const val SAVE = "notebook.note.save"
    const val FLUSH = "notebook.note.flush"

    /** The request body naming one document. */
    fun request(id: NotebookDocumentId, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject { put("document_id", id.value); fields() }

    /** A snapshot as the routes send it. */
    fun snapshot(value: NotebookNoteSnapshot): JsonObject = buildJsonObject {
        put("document_id", value.id.value)
        put("title", value.title)
        put("markdown", value.markdown)
    }

    /** The open result for an already authorized load. */
    fun load(result: NotebookNoteLoad): JsonObject = when (result) {
        is NotebookNoteLoad.Found -> response(FOUND) { put("snapshot", snapshot(result.snapshot)) }
        NotebookNoteLoad.Denied -> response(DENIED)
        NotebookNoteLoad.NotFound -> response(MISSING)
        is NotebookNoteLoad.Unavailable -> response(UNAVAILABLE) { put("reason", result.reason) }
    }

    /** The save or flush result. */
    fun save(result: NotebookNoteSave): JsonObject = when (result) {
        is NotebookNoteSave.Saved -> response(SAVED) { put("snapshot", snapshot(result.snapshot)) }
        is NotebookNoteSave.Conflict -> response(CONFLICT) {
            put("current", snapshot(result.current))
            result.durable?.let { put("durable", snapshot(it)) }
        }
        is NotebookNoteSave.PendingDurability -> response(PENDING_DURABILITY) {
            put("applied", snapshot(result.applied))
            put("reason", result.reason)
        }
        NotebookNoteSave.Denied -> response(DENIED)
        is NotebookNoteSave.Unavailable -> response(UNAVAILABLE) { put("reason", result.reason) }
    }

    /** The reference result. */
    fun reference(result: NotebookReferenceLoad): JsonObject = when (result) {
        is NotebookReferenceLoad.Found -> response(FOUND) {
            put("document", Json.encodeToJsonElement(NotebookDocument.serializer(), result.document))
        }
        NotebookReferenceLoad.Denied -> response(DENIED)
        NotebookReferenceLoad.Missing -> response(MISSING)
        NotebookReferenceLoad.Unavailable -> response(UNAVAILABLE)
    }

    /** Read an open result for [id]; a snapshot for another document is unavailable. */
    fun readLoad(id: NotebookDocumentId, result: JsonElement?): NotebookNoteLoad {
        val body = result as? JsonObject ?: return NotebookNoteLoad.Unavailable(NO_RESULT)
        return when (body.status()) {
            FOUND -> body.snapshotAt("snapshot", id)?.let(NotebookNoteLoad::Found)
                ?: NotebookNoteLoad.Unavailable(MALFORMED)
            DENIED -> NotebookNoteLoad.Denied
            MISSING -> NotebookNoteLoad.NotFound
            else -> NotebookNoteLoad.Unavailable(body.reason())
        }
    }

    /** Read a save or flush result for [id]. */
    fun readSave(id: NotebookDocumentId, result: JsonElement?): NotebookNoteSave {
        val body = result as? JsonObject ?: return NotebookNoteSave.Unavailable(NO_RESULT)
        return when (body.status()) {
            SAVED -> body.snapshotAt("snapshot", id)?.let(NotebookNoteSave::Saved)
            CONFLICT -> body.snapshotAt("current", id)?.let { NotebookNoteSave.Conflict(it, body.snapshotAt("durable", id)) }
            PENDING_DURABILITY -> body.snapshotAt("applied", id)?.let { NotebookNoteSave.PendingDurability(it, body.reason()) }
            DENIED -> NotebookNoteSave.Denied
            else -> NotebookNoteSave.Unavailable(body.reason())
        } ?: NotebookNoteSave.Unavailable(MALFORMED)
    }

    /** Read a reference result. */
    fun readReference(result: JsonElement?): NotebookReferenceLoad {
        val body = result as? JsonObject ?: return NotebookReferenceLoad.Unavailable
        return when (body.status()) {
            FOUND -> body["document"]
                ?.let { runCatching { Json.decodeFromJsonElement(NotebookDocument.serializer(), it) }.getOrNull() }
                ?.let(NotebookReferenceLoad::Found) ?: NotebookReferenceLoad.Unavailable
            DENIED -> NotebookReferenceLoad.Denied
            MISSING -> NotebookReferenceLoad.Missing
            else -> NotebookReferenceLoad.Unavailable
        }
    }
}

private fun JsonObject.status(): String? = text("status")

private fun JsonObject.reason(): String = text("reason") ?: "The host could not complete the request"

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.snapshotAt(key: String, id: NotebookDocumentId): NotebookNoteSnapshot? {
    val item = (get(key) as? JsonObject)?.takeIf { it.text("document_id") == id.value }
    val title = item?.text("title")
    val markdown = item?.text("markdown")
    return if (title != null && markdown != null) NotebookNoteSnapshot(id, title, markdown) else null
}

private inline fun response(status: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject { put("status", status); fields() }

private const val FOUND = "found"
private const val DENIED = "denied"
private const val MISSING = "missing"
private const val UNAVAILABLE = "unavailable"
private const val SAVED = "saved"
private const val CONFLICT = "conflict"
private const val PENDING_DURABILITY = "pending_durability"
private const val NO_RESULT = "The host returned no result"
private const val MALFORMED = "The host returned a malformed note"
