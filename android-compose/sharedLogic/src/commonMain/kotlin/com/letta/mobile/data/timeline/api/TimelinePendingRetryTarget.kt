package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** No-runtime hosts delegate metadata handling here; actual writes still fail loudly. */
fun unboundTimelineWriterResult(member: String): Any? =
    when (member.substringBefore('-')) {
    "queuedRetries" -> emptyFlow<List<Pair<ConversationId, CanonicalPendingLocalStore.Record>>>()
    "reconcileRecentMessages" -> com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Skipped("no_runtime")
    else -> error("No chat runtime is captured for this backend ($member)")
}

/** Retries waiting in the outbox, which the agent's send coordinator dispatches. */
interface TimelinePendingRetryTarget {
    /**
     * Each conversation's prompts that a Retry queued and nothing has dispatched yet. The queued
     * retry is durable: a sender that subscribes later still receives it. Targets without pending
     * storage have none.
     */
    fun queuedRetries(agent: AgentId): Flow<List<Pair<ConversationId, CanonicalPendingLocalStore.Record>>> = emptyFlow()
}
