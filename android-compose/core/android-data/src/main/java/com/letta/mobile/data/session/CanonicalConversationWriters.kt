package com.letta.mobile.data.session

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-by4vm.15: the canonical writer each bound conversation currently has, so session-level
 * recovery (frame-collector overflow) reconciles the same ledger the open chat pages from. A
 * conversation nobody has bound has no visible timeline to repair; its next open pages from the server.
 */
@Singleton
class CanonicalConversationWriters @Inject constructor() {
    private val writers = mutableMapOf<ConversationId, TimelineExternalTransportWriter>()

    fun register(conversationId: ConversationId, writer: TimelineExternalTransportWriter) = synchronized(writers) {
        writers[conversationId] = writer
    }

    /** Removes [writer] only while it is still the registered one; a newer binding keeps its slot. */
    fun unregister(conversationId: ConversationId, writer: TimelineExternalTransportWriter) = synchronized(writers) {
        if (writers[conversationId] === writer) writers.remove(conversationId)
    }

    fun writerFor(conversationId: ConversationId): TimelineExternalTransportWriter? = synchronized(writers) { writers[conversationId] }
}
