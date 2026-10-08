package com.letta.mobile.data.transport.api

import com.letta.mobile.data.transport.ChannelTransportDefaults
import com.letta.mobile.data.transport.ServerFrame

/**
 * Encapsulated parameter payload for pausing a scheduled cron task.
 */
data class CronPauseCommand(
    val taskId: String,
    val timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
)

/**
 * Encapsulated parameter payload for resuming a scheduled cron task.
 */
data class CronResumeCommand(
    val taskId: String,
    val scheduledFor: String? = null,
    val timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
)

/**
 * Transport capability for controlling scheduled cron tasks (pause and resume).
 * Separate from base [IChannelTransport] to keep transport interfaces cohesive.
 */
interface CronControlTransport {
    suspend fun sendCronPause(command: CronPauseCommand): ServerFrame.CronPauseResponse
    suspend fun sendCronResume(command: CronResumeCommand): ServerFrame.CronResumeResponse
}
