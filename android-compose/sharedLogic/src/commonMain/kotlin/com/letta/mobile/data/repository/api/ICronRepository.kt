package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.CronAddParams
import kotlinx.coroutines.flow.Flow

data class CronScheduleRef(
    val agentId: String,
    val taskId: String,
)

interface ICronRepository {
    fun schedulesFlow(agentId: String): Flow<List<CronTask>>
    suspend fun refresh(agentId: String): Result<List<CronTask>>
    suspend fun addSchedule(params: CronAddParams): Result<CronTask>
    suspend fun deleteSchedule(agentId: String, taskId: String): Result<Unit>
    suspend fun pauseSchedule(target: CronScheduleRef): Result<Unit>
    suspend fun pauseSchedule(agentId: String, taskId: String): Result<Unit> =
        pauseSchedule(CronScheduleRef(agentId, taskId))
    suspend fun resumeSchedule(target: CronScheduleRef, scheduledFor: String? = null): Result<Unit>
    suspend fun resumeSchedule(agentId: String, taskId: String, scheduledFor: String? = null): Result<Unit> =
        resumeSchedule(CronScheduleRef(agentId, taskId), scheduledFor)
}
