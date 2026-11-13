package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.mayEditNotebook
import com.letta.mobile.data.canvas.mayReadNotebook
import com.letta.mobile.data.canvas.mayReferenceNotebook

/**
 * Host-owned grants keyed by an authenticated principal and the exact logical document ID.
 * Implementations serialize the entire [action] with provisioning and revocation, including
 * across instances that share a store. A missing grant is passed as null; storage failures throw.
 * Callers must not derive [principal] from a request or notebook content.
 */
interface NotebookGrantStore {
    /** Run [action] under the grant transaction; no operation may escape this transaction. */
    suspend fun <T> withGrant(
        principal: String,
        documentId: NotebookDocumentId,
        action: suspend (NotebookPeerGrant?) -> T,
    ): T

    /**
     * Run [action] under one grant transaction over the grants [principal] holds for [documentIds],
     * read once: the map holds each of those documents that has a grant (a missing grant is absent).
     * Like [withGrant], the transaction is serialized with provisioning and revocation for the whole
     * [action], so a decision made from the map still holds for everything the action does with it.
     * Keep [documentIds] bounded: the transaction lasts as long as the action takes over all of them.
     */
    suspend fun <T> withGrants(
        principal: String,
        documentIds: Collection<NotebookDocumentId>,
        action: suspend (Map<NotebookDocumentId, NotebookPeerGrant>) -> T,
    ): T

    /**
     * The documents [principal] holds a grant for, read in one transaction, ordered by
     * [NotebookDocumentId.value] (ascending, by [String.compareTo]) so a position in the list means
     * the same thing on every call. A listing authorizes nothing: every operation on a listed
     * document still runs [withGrant].
     */
    suspend fun documentsOf(principal: String): List<NotebookDocumentId>
}

/** References require both capabilities for the same exact grant. */
fun mayResolveNotebookReference(grant: NotebookPeerGrant?): Boolean =
    mayReadNotebook(grant) && mayReferenceNotebook(grant)

/** Content-bearing editor operations require both read and edit capabilities. */
fun mayEditNotebookContent(grant: NotebookPeerGrant?): Boolean =
    mayReadNotebook(grant) && mayEditNotebook(grant)
