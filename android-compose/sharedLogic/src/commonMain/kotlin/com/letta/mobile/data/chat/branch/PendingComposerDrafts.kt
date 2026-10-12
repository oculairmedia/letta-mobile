package com.letta.mobile.data.chat.branch

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** A branch's prompt waiting for the fork's chat: put in its composer, and sent at once for a rerun. */
data class PendingDraft(val draft: BranchDraft, val sendNow: Boolean = false)

/**
 * letta-mobile-bzvro.16, letta-mobile-7mys7: hands an "Edit and resend" or "Rerun" prompt to the
 * chat that opens the forked conversation. On Android the fork opens as a new chat route with its
 * own view model, so the prompt crosses the navigation here: the branch [put]s it under the fork's
 * id and that chat [take]s it once when it opens. Desktop sets its composer directly and does not
 * need it.
 */
class PendingComposerDrafts {
    private val lock = SynchronizedObject()
    private val drafts = LinkedHashMap<String, PendingDraft>()

    fun put(conversationId: String, draft: PendingDraft) = synchronized(lock) {
        drafts[conversationId] = draft
        // Abandoned hand-offs (the fork never opened) must not pile up.
        while (drafts.size > MAX_PENDING) drafts.remove(drafts.keys.first())
    }

    /** Leaves plain [text] for the composer (the canvas bubble's unsent words). */
    fun put(conversationId: String, text: String) = put(conversationId, PendingDraft(BranchDraft(text)))

    /** The prompt left for [conversationId], removed so it is applied once. */
    fun take(conversationId: String): PendingDraft? = synchronized(lock) { drafts.remove(conversationId) }

    companion object {
        private const val MAX_PENDING = 8

        /** Process-wide: the route that puts and the route that takes have no shared owner. */
        val shared: PendingComposerDrafts = PendingComposerDrafts()
    }
}
