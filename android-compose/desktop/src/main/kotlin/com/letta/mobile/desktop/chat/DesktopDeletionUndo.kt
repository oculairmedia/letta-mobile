package com.letta.mobile.desktop.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-bzvro.31: the conversation a delete just archived, which the shell offers to bring
 * back (the undo snackbar). Empty when nothing is pending or where delete is permanent.
 */
class DesktopDeletionUndo {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(conversationId: String) {
        _pending.value = conversationId
    }

    /** The snackbar timed out, was dismissed or was acted on: the offer is spent. */
    fun clear(conversationId: String) {
        _pending.update { if (it == conversationId) null else it }
    }
}
