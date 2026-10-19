package com.letta.mobile.data.schedules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The cron task operations a backend offers to the desktop cron panel. removed
 * the Letta REST implementation (`/v1/crons`); the App Server's `cron_*` commands are the
 * replacement (wired by the shared Schedules page, ckmub S14). Until then the panel shows as
 * unavailable unless an Iroh host relays schedules.
 */
interface CronApi : AutoCloseable {
    suspend fun listCrons(): List<CronTask>

    suspend fun deleteCron(id: String)

    suspend fun pauseCron(id: String)

    suspend fun resumeCron(id: String)

    /** Create a recurring cron task for [agentId]. */
    suspend fun createCron(
        agentId: String,
        name: String,
        description: String,
        prompt: String,
        cron: String,
        timezone: String,
        recurring: Boolean = true,
    )

    override fun close() = Unit
}

@Serializable
data class CronTask(
    val id: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val recurring: Boolean = false,
    val prompt: String? = null,
)
