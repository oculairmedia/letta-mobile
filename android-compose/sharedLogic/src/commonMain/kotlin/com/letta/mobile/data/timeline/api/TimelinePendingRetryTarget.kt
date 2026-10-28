package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore

/** Captured sender registration shared by canonical writers and their wrappers. */
interface TimelinePendingRetryTarget {
    /** Bind the captured sender; targets without pending storage may ignore it. */
    fun bindPendingRetry(agent: AgentId, sender: suspend (ConversationId, CanonicalPendingLocalStore.Record) -> Unit) {}
}
