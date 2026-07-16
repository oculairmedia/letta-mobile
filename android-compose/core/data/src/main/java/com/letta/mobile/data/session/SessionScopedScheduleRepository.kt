package com.letta.mobile.data.session

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IScheduleRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

internal fun defaultSessionScopedScheduleRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedScheduleRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IScheduleRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedScheduleRepositoryScope(),
    )

    private val current: IScheduleRepository
        get() = sessionManager.current.scheduleRepository

    override fun getSchedules(agentId: String): Flow<List<ScheduledMessage>> =
        sessionManager.currentGraph.flatMapLatest { it.scheduleRepository.getSchedules(agentId) }

    override suspend fun refreshSchedules(agentId: String, limit: Int?, after: String?) =
        sessionManager.withCurrentSession { it.scheduleRepository.refreshSchedules(agentId, limit, after) }

    override suspend fun getSchedule(agentId: String, scheduledMessageId: String): ScheduledMessage =
        sessionManager.withCurrentSession { it.scheduleRepository.getSchedule(agentId, scheduledMessageId) }

    override suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage =
        sessionManager.withCurrentSession { it.scheduleRepository.createSchedule(agentId, params) }

    override suspend fun deleteSchedule(agentId: String, scheduledMessageId: String) =
        sessionManager.withCurrentSession { it.scheduleRepository.deleteSchedule(agentId, scheduledMessageId) }

    fun close() { proxyScope.cancel() }
}
