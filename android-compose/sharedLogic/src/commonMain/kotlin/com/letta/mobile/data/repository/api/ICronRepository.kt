package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.CronAddParams
import kotlinx.coroutines.flow.Flow

interface ICronRepository {
    fun schedulesFlow(agentId: String): Flow<List<CronTask>>
    suspend fun refresh(agentId: String): Result<List<CronTask>>
    suspend fun addSchedule(params: CronAddParams): Result<CronTask>
    suspend fun deleteSchedule(agentId: String, taskId: String): Result<Unit>
}
