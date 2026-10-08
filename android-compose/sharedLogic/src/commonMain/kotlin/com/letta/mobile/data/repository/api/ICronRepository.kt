package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.CronAddParams
import kotlinx.coroutines.flow.Flow

data class AgentScheduleScope(
    val agentId: String,
)

data class CronScheduleRef(
    val agentId: String,
    val taskId: String,
)

data class CronResumeTarget(
    val target: CronScheduleRef,
    val scheduledFor: String? = null,
)

interface ICronRepository {
    fun schedulesFlow(scope: AgentScheduleScope): Flow<List<CronTask>>
    fun schedulesFlow(agentId: String): Flow<List<CronTask>> =
        schedulesFlow(AgentScheduleScope(agentId))

    suspend fun refresh(scope: AgentScheduleScope): Result<List<CronTask>>
    suspend fun refresh(agentId: String): Result<List<CronTask>> =
        refresh(AgentScheduleScope(agentId))

    suspend fun addSchedule(params: CronAddParams): Result<CronTask>

    suspend fun deleteSchedule(target: CronScheduleRef): Result<Unit>
    suspend fun deleteSchedule(agentId: String, taskId: String): Result<Unit> =
        deleteSchedule(CronScheduleRef(agentId, taskId))

    suspend fun pauseSchedule(target: CronScheduleRef): Result<Unit>
    suspend fun pauseSchedule(agentId: String, taskId: String): Result<Unit> =
        pauseSchedule(CronScheduleRef(agentId, taskId))

    suspend fun resumeSchedule(target: CronResumeTarget): Result<Unit>
    suspend fun resumeSchedule(target: CronScheduleRef, scheduledFor: String? = null): Result<Unit> =
        resumeSchedule(CronResumeTarget(target, scheduledFor))
    suspend fun resumeSchedule(agentId: String, taskId: String, scheduledFor: String? = null): Result<Unit> =
        resumeSchedule(CronScheduleRef(agentId, taskId), scheduledFor)
}
