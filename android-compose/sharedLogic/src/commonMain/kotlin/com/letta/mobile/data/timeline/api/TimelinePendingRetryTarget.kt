package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore

/** No-runtime hosts delegate metadata handling here; actual writes still fail loudly. */
fun unboundTimelineWriterResult(member: String): com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome? = when (member.substringBefore('-')) {
    "bindPendingRetry" -> null
    "reconcileRecentMessages" -> com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Skipped("no_runtime")
    else -> error("No chat runtime is captured for this backend ($member)")
}

/** Captured sender registration shared by canonical writers and their wrappers. */
interface TimelinePendingRetryTarget {
    /** Bind the captured sender; targets without pending storage may ignore it. */
    fun bindPendingRetry(agent: AgentId, sender: suspend (ConversationId, CanonicalPendingLocalStore.Record) -> Unit) {}
}
