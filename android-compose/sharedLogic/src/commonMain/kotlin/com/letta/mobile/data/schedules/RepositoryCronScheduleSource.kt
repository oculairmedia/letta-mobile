package com.letta.mobile.data.schedules

import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.model.AgentId

/** Host-confirmed list and controls over the session's shared cron repository. */
class RepositoryCronScheduleSource(override val repository: ICronRepository) : CronScheduleSource {
    override suspend fun listCrons(agentId: AgentId?): List<CronTask> {
        if (agentId == null) return emptyList()
        return repository.refresh(agentId.value).getOrThrow().map { task ->
            CronTask(
                id = task.id, agentId = task.agentId, conversationId = task.conversationId,
                name = task.name, description = task.description, cron = task.cron, timezone = task.timezone,
                recurring = task.recurring, prompt = task.prompt, status = task.status,
                lastFiredAt = task.lastFiredAt, missedAt = task.missedAt, fireCount = task.fireCount,
                scheduledFor = task.scheduledFor,
            )
        }
    }
}
