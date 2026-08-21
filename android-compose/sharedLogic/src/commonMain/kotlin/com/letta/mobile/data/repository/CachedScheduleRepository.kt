package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ScheduleRemoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 5d: platform-neutral cached schedule repository. Android supplies HTTP
 * [ScheduleRemoteSource]. Desktop continues to use
 * [com.letta.mobile.data.repository.iroh.IrohScheduleRepository] for iroh://.
 *
 * Cache contract: a refresh with `after == null` replaces the agent page (typical
 * first-page load). A refresh with non-null `after` merges and deduplicates into
 * the existing agent cache so paginated pages accumulate. Mutations for a given
 * `agentId` are serialized so a delayed refresh cannot restore a deleted schedule.
 */
open class CachedScheduleRepository(
    private val remote: ScheduleRemoteSource,
) : IScheduleRepository {
    private val _schedules = MutableStateFlow<Map<String, List<ScheduledMessage>>>(emptyMap())
    private val agentLocks = mutableMapOf<String, Mutex>()
    private val agentLocksGuard = Mutex()

    private suspend fun lockFor(agentId: String): Mutex =
        agentLocksGuard.withLock {
            agentLocks.getOrPut(agentId) { Mutex() }
        }

    override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> {
        return _schedules.asStateFlow().map { current -> current[agentId] ?: emptyList() }
    }

    override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) {
        lockFor(agentId).withLock {
            val response = remote.listSchedules(agentId = agentId, limit = limit, after = after)
            val page = response.scheduledMessages
            _schedules.update { current ->
                current.toMutableMap().apply {
                    if (after == null) {
                        put(agentId, page)
                    } else {
                        val existing = get(agentId).orEmpty()
                        val merged = (existing + page).distinctBy { it.id }
                        put(agentId, merged)
                    }
                }
            }
        }
    }

    override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage {
        return remote.retrieveSchedule(agentId, scheduledMessageId)
    }

    override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
        return lockFor(agentId).withLock {
            val schedule = remote.createSchedule(agentId, params)
            val response = remote.listSchedules(agentId = agentId, limit = 100, after = null)
            _schedules.update { current ->
                current.toMutableMap().apply { put(agentId, response.scheduledMessages) }
            }
            schedule
        }
    }

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) {
        lockFor(agentId).withLock {
            remote.deleteSchedule(agentId, scheduledMessageId)
            _schedules.update { current ->
                current.toMutableMap().apply {
                    val existing = get(agentId) ?: emptyList()
                    put(agentId, existing.filterNot { it.id == scheduledMessageId })
                }
            }
        }
    }
}
