package com.letta.mobile.data.chat.branch

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-bzvro.16 (F16): hands an "Edit and resend" draft to the chat that opens the
 * forked conversation. On Android the fork opens as a new chat route with its own view model, so
 * the text crosses the navigation here: the edit [put]s it under the fork's id and that chat
 * [take]s it once when it opens. Desktop sets its composer directly and does not need it.
 */
class PendingComposerDrafts {
    private val lock = SynchronizedObject()
    private val drafts = LinkedHashMap<String, String>()

    fun put(conversationId: String, draft: String) = synchronized(lock) {
        drafts[conversationId] = draft
        // Abandoned hand-offs (the fork never opened) must not pile up.
        while (drafts.size > MAX_PENDING) drafts.remove(drafts.keys.first())
    }

    /** The draft left for [conversationId], removed so it is applied once. */
    fun take(conversationId: String): String? = synchronized(lock) { drafts.remove(conversationId) }

    companion object {
        private const val MAX_PENDING = 8

        /** Process-wide: the route that puts and the route that takes have no shared owner. */
        val shared: PendingComposerDrafts = PendingComposerDrafts()
    }
}
