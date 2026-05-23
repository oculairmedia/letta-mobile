package com.letta.mobile.data.session

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.CronAddParams
import com.letta.mobile.data.repository.api.ICronRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedCronRepository @Inject constructor(
    private val sessionManager: SessionManager,
) : ICronRepository {
    private val current: ICronRepository
        get() = sessionManager.current.cronRepository

    override fun schedulesFlow(agentId: String): Flow<List<CronTask>> =
        sessionManager.currentGraph.flatMapLatest { it.cronRepository.schedulesFlow(agentId) }

    override suspend fun refresh(agentId: String): Result<List<CronTask>> = current.refresh(agentId)
    override suspend fun addSchedule(params: CronAddParams): Result<CronTask> = current.addSchedule(params)
    override suspend fun deleteSchedule(agentId: String, taskId: String): Result<Unit> = current.deleteSchedule(agentId, taskId)
}
