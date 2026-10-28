package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Retains sender bindings across lazy conversation resolution in a captured writer. */
class TimelinePendingRetryBinding {
    private val lock = SynchronizedObject()
    private val senders = mutableMapOf<AgentId, suspend (ConversationId, CanonicalPendingLocalStore.Record) -> Unit>()
    private val writers = mutableSetOf<TimelineExternalTransportWriter>()

    /** Forwards to existing conversations and remembers the sender for later ones. */
    fun bind(agent: AgentId, sender: suspend (ConversationId, CanonicalPendingLocalStore.Record) -> Unit) {
        synchronized(lock) {
            senders[agent] = sender
            writers.forEach { it.bindPendingRetry(agent, sender) }
        }
    }

    /** Applies every retained binding before a newly resolved writer is used. */
    fun resolved(writer: TimelineExternalTransportWriter) {
        synchronized(lock) {
            if (writers.add(writer)) senders.forEach { (agent, sender) -> writer.bindPendingRetry(agent, sender) }
        }
    }
}
