package com.letta.mobile.wrapper.notebook

import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.authority.HostNotebookAccess
import com.letta.mobile.data.canvas.authority.NotebookGrantStore
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.adminError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Installs notebook operations on the authenticated host router. No request field selects a principal. */
fun registerNotebookAdminRoutes(router: AdminRpcRouter, notebooks: NotebookLocalStore, grants: NotebookGrantStore) {
    notebooks.requireGrantIsolation()
    fun access(context: AdminRpcRequestContext): HostNotebookAccess {
        val principal = context.authenticatedEndpointId?.takeIf { context.authenticated && it.matches(ENDPOINT_ID) }
            ?: adminError("unauthorized")
        return HostNotebookAccess(notebooks, grants, principal)
    }
    router.registerScoped("notebook.reference.resolve") { params, context ->
        val id = documentId(params)
        when (val result = access(context).resolver.resolve(id)) {
            is NotebookReferenceLoad.Found -> response("found") {
                put("document", Json.encodeToJsonElement(NotebookDocument.serializer(), result.document))
            }
            NotebookReferenceLoad.Denied -> response("denied")
            NotebookReferenceLoad.Missing -> response("missing")
            NotebookReferenceLoad.Unavailable -> response("unavailable")
        }
    }
    router.registerScoped("notebook.note.open") { params, context ->
        val id = documentId(params)
        val binding = access(context)
        when (binding.opener.open(id)) {
            DocumentWorkspaceOpen.Denied -> response("denied")
            DocumentWorkspaceOpen.Unavailable -> response("unavailable")
            is DocumentWorkspaceOpen.Opened -> when (val loaded = binding.backend.load(id)) {
                is NotebookNoteLoad.Found -> response("found") { put("snapshot", snapshot(loaded.snapshot)) }
                NotebookNoteLoad.Denied -> response("denied")
                NotebookNoteLoad.NotFound -> response("missing")
                is NotebookNoteLoad.Unavailable -> response("unavailable") { put("reason", loaded.reason) }
            }
        }
    }
    router.registerScoped("notebook.note.save") { params, context ->
        val id = documentId(params)
        saveResponse(access(context).backend.save(id, string(params, "expected"), string(params, "replacement")))
    }
    router.registerScoped("notebook.note.flush") { params, context ->
        val id = documentId(params)
        val item = params?.get("snapshot")?.jsonObject ?: adminError("snapshot is required")
        if (string(item, "document_id") != id.value) adminError("snapshot identity mismatch")
        val applied = NotebookNoteSnapshot(id, string(item, "title"), string(item, "markdown"))
        saveResponse(access(context).backend.flush(applied))
    }
}

private fun documentId(params: JsonObject?): NotebookDocumentId {
    val id = string(params, "document_id")
    if (!id.matches(DOCUMENT_ID)) adminError("invalid document_id")
    return NotebookDocumentId(id)
}

private fun string(params: JsonObject?, key: String): String {
    val value = params?.get(key) as? JsonPrimitive ?: adminError("$key is required")
    if (!value.isString) adminError("$key must be a string")
    return value.content
}

private fun snapshot(value: NotebookNoteSnapshot): JsonObject = buildJsonObject {
    put("document_id", value.id.value)
    put("title", value.title)
    put("markdown", value.markdown)
}

private fun saveResponse(result: NotebookNoteSave): JsonObject = when (result) {
    is NotebookNoteSave.Saved -> response("saved") { put("snapshot", snapshot(result.snapshot)) }
    is NotebookNoteSave.Conflict -> response("conflict") {
        put("current", snapshot(result.current))
        result.durable?.let { put("durable", snapshot(it)) }
    }
    is NotebookNoteSave.PendingDurability -> response("pending_durability") {
        put("applied", snapshot(result.applied))
        put("reason", result.reason)
    }
    NotebookNoteSave.Denied -> response("denied")
    is NotebookNoteSave.Unavailable -> response("unavailable") { put("reason", result.reason) }
}

private inline fun response(status: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject { put("status", status); fields() }

private val ENDPOINT_ID = Regex("[0-9a-f]{64}")
private val DOCUMENT_ID = Regex("[0-9a-f]{32}")
