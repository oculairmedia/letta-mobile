package com.letta.mobile.data.canvas.references

import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookDocumentId

/** A trusted host's current decision for reading and embedding one notebook target. */
enum class NotebookReferenceAccess {
    /** The host established access for the current caller and target. */
    ALLOWED,
    /** The host refused access; storage must not be consulted. */
    DENIED,
    /** The host cannot currently establish access; storage must not be consulted. */
    UNAVAILABLE,
}

/** Absence, refusal and failure are distinct from a successfully loaded empty document. */
sealed interface NotebookReferenceLoad {
    /** The exact requested document, including its opaque items. */
    data class Found(val document: NotebookDocument) : NotebookReferenceLoad
    /** The trusted authority refused this target. */
    data object Denied : NotebookReferenceLoad
    /** An authorized lookup found no document. */
    data object Missing : NotebookReferenceLoad
    /** Access or storage could not currently be established. */
    data object Unavailable : NotebookReferenceLoad
}

/**
 * Mandatory authorization-before-read boundary, supplied by a trusted host. [authorize] must check
 * the current caller's right to read and embed each exact target on every invocation, including
 * repeated targets. It must not infer ownership from index membership, UI state or document data.
 * [readAuthorized] runs only after that decision. Neither callback has a permissive default.
 * This ordering is enforcement, not an authority implementation: hosts still supply real policy.
 * No grants are persisted here. Opening an editor requires a new host authorization decision.
 * Cancellation and callback failures propagate to the caller.
 */
class NotebookReferenceResolver(
    private val authorize: suspend (NotebookDocumentId) -> NotebookReferenceAccess,
    private val readAuthorized: suspend (NotebookDocumentId) -> NotebookReferenceLoad,
) {
    /** Authorize [id] before any lookup, and refuse a source that returns another identity. */
    suspend fun resolve(id: NotebookDocumentId): NotebookReferenceLoad = when (authorize(id)) {
        NotebookReferenceAccess.DENIED -> NotebookReferenceLoad.Denied
        NotebookReferenceAccess.UNAVAILABLE -> NotebookReferenceLoad.Unavailable
        NotebookReferenceAccess.ALLOWED -> when (val result = readAuthorized(id)) {
            is NotebookReferenceLoad.Found -> if (result.document.id == id) result else NotebookReferenceLoad.Unavailable
            else -> result
        }
    }
}
