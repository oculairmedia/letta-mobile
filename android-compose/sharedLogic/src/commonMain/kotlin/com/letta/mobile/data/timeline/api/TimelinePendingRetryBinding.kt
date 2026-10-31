package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/** Gathers queued retries across a captured writer's lazily resolved conversations. */
class TimelinePendingRetryBinding {
    private val writers = MutableStateFlow<Set<TimelineExternalTransportWriter>>(emptySet())

    /** Every conversation writer resolved so far, including ones resolved after subscription. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun queuedRetries(agent: AgentId): Flow<List<Pair<ConversationId, CanonicalPendingLocalStore.Record>>> =
        writers.flatMapLatest { resolved ->
            if (resolved.isEmpty()) flowOf(emptyList())
            else combine(resolved.map { it.queuedRetries(agent) }) { lists -> lists.flatMap { it } }
        }

    /** Adds a newly resolved writer to [queuedRetries]. */
    fun resolved(writer: TimelineExternalTransportWriter) {
        writers.update { it + writer }
    }
}
