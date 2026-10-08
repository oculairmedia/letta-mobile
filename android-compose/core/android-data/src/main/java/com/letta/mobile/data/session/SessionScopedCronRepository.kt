package com.letta.mobile.data.session

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.CronAddParams
import com.letta.mobile.data.repository.api.AgentScheduleScope
import com.letta.mobile.data.repository.api.CronResumeTarget
import com.letta.mobile.data.repository.api.CronScheduleRef
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
    override fun schedulesFlow(scope: AgentScheduleScope): Flow<List<CronTask>> =
        sessionManager.currentGraph.flatMapLatest { it.cronRepository.schedulesFlow(scope) }

    override suspend fun refresh(scope: AgentScheduleScope): Result<List<CronTask>> = sessionManager.withCurrentSession { it.cronRepository.refresh(scope) }
    override suspend fun addSchedule(params: CronAddParams): Result<CronTask> = sessionManager.withCurrentSession { it.cronRepository.addSchedule(params) }
    override suspend fun deleteSchedule(target: CronScheduleRef): Result<Unit> = sessionManager.withCurrentSession { it.cronRepository.deleteSchedule(target) }
    override suspend fun pauseSchedule(target: CronScheduleRef): Result<Unit> = sessionManager.withCurrentSession { it.cronRepository.pauseSchedule(target) }
    override suspend fun resumeSchedule(target: CronResumeTarget): Result<Unit> = sessionManager.withCurrentSession { it.cronRepository.resumeSchedule(target) }
}
