package com.letta.mobile.data.timeline

/** Exact shared semantics; storage implements only scoped point/index operations. */
internal object CanonicalToolIndex {
    suspend fun observe(transaction: TimelineStoreTransaction, callId: String, owner: TimelineMessageId?, returned: Boolean): Boolean {
        require(callId.isNotBlank())
        val previous = transaction.toolCall(callId)
        check(previous?.owner == null || owner == null || previous.owner == owner) {
            "Tool call identity has conflicting canonical owners: $callId"
        }
        val next = TimelineToolIndexEntry(callId, previous?.owner ?: owner, returned || previous?.returned == true)
        if (next == previous) return false
        transaction.putToolCall(next)
        return true
    }

    suspend fun advanceGeneration(transaction: TimelineStoreTransaction): Long {
        val current = transaction.toolSweepGeneration()
        check(current < Long.MAX_VALUE)
        return (current + 1).also { transaction.setToolSweepGeneration(it) }
    }

    /** Rechecked in the settlement transaction, never from a detached page snapshot. */
    suspend fun stillUnresolved(transaction: TimelineStoreTransaction, generation: Long, callId: String, owner: TimelineMessageId): Boolean =
        transaction.toolSweepGeneration() == generation && transaction.toolCall(callId) == TimelineToolIndexEntry(callId, owner, false)
}
