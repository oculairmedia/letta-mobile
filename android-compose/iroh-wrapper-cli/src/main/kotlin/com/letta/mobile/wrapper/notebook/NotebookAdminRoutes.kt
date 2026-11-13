package com.letta.mobile.wrapper.notebook

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.authority.HostNotebookAccess
import com.letta.mobile.data.canvas.authority.NotebookAccessWire
import com.letta.mobile.data.canvas.authority.NotebookGrantStore
import com.letta.mobile.data.canvas.authority.SharedNotesWire
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.canvas.workspace.SharedNoteListing
import com.letta.mobile.data.canvas.workspace.SharedNotesCursor
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.adminError
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Installs notebook operations on the authenticated host router. No request field selects a principal.
 * At most one `notebook.shared.list` runs per principal at a time; another call from the same principal
 * meanwhile is answered at once, unavailable with [SharedNotesWire.BUSY], and never queued.
 */
fun registerNotebookAdminRoutes(router: AdminRpcRouter, notebooks: NotebookLocalStore, grants: NotebookGrantStore) {
    notebooks.requireGrantIsolation()
    fun access(context: AdminRpcRequestContext): HostNotebookAccess = HostNotebookAccess(notebooks, grants, principalOf(context))
    val listing = ConcurrentHashMap.newKeySet<String>()
    router.registerScoped(NotebookAccessWire.RESOLVE) { params, context ->
        val id = documentId(params)
        NotebookAccessWire.reference(access(context).resolver.resolve(id))
    }
    router.registerScoped(SharedNotesWire.LIST) { params, context ->
        val principal = principalOf(context)
        val from = listCursor(params)
        SharedNotesWire.sharedNotes(listing.oneAtATime(principal) { HostNotebookAccess(notebooks, grants, principal).sharedNotes(from) })
    }
    router.registerScoped(NotebookAccessWire.OPEN) { params, context ->
        val id = documentId(params)
        val binding = access(context)
        when (binding.opener.open(id)) {
            DocumentWorkspaceOpen.Denied -> NotebookAccessWire.load(NotebookNoteLoad.Denied)
            DocumentWorkspaceOpen.Unavailable -> NotebookAccessWire.load(NotebookNoteLoad.Unavailable("The note could not be opened"))
            is DocumentWorkspaceOpen.Opened -> NotebookAccessWire.load(binding.backend.load(id))
        }
    }
    router.registerScoped(NotebookAccessWire.SAVE) { params, context ->
        val id = documentId(params)
        NotebookAccessWire.save(access(context).backend.save(id, string(params, "expected"), string(params, "replacement")))
    }
    router.registerScoped(NotebookAccessWire.FLUSH) { params, context ->
        val id = documentId(params)
        val item = params?.get("snapshot")?.jsonObject ?: adminError("snapshot is required")
        if (string(item, "document_id") != id.value) adminError("snapshot identity mismatch")
        val applied = NotebookNoteSnapshot(id, string(item, "title"), string(item, "markdown"))
        NotebookAccessWire.save(access(context).backend.flush(applied))
    }
}

/** The authenticated transport identity; never a request field. */
private fun principalOf(context: AdminRpcRequestContext): String {
    return context.authenticatedEndpointId?.takeIf { context.authenticated && it.matches(ENDPOINT_ID) }
        ?: adminError("unauthorized")
}

/** Runs [list] unless one is already running for [principal], which is answered busy at once instead. */
private suspend fun MutableSet<String>.oneAtATime(principal: String, list: suspend () -> SharedNoteListing): SharedNoteListing {
    if (!add(principal)) return SharedNoteListing.Unavailable(SharedNotesWire.BUSY)
    try {
        return list()
    } finally {
        remove(principal)
    }
}

/** The page a listing request asks for: no field but `cursor`, which must be a string; the host reads its content. */
private fun listCursor(params: JsonObject?): SharedNotesCursor? {
    val fields = params?.keys.orEmpty()
    if (!fields.all { it == SharedNotesWire.CURSOR }) adminError("unknown field")
    if (fields.isEmpty()) return null
    return SharedNotesCursor(string(params, SharedNotesWire.CURSOR))
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

private val ENDPOINT_ID = Regex("[0-9a-f]{64}")
private val DOCUMENT_ID = Regex("[0-9a-f]{32}")
