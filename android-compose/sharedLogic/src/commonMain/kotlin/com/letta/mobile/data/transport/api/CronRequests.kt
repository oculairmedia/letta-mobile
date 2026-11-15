package com.letta.mobile.data.transport.api

import kotlin.jvm.JvmInline

/**
 * The identity of a task served by the cron transport.
 *
 * @property value the host's task id.
 */
@JvmInline
value class CronTaskRef(val value: String)

/**
 * The agent whose cron tasks are addressed by an operation.
 *
 * @property value the agent id.
 */
@JvmInline
value class CronAgentRef(val value: String)

/**
 * Optional scope for listing cron tasks.
 *
 * @property agentId lists only this agent's tasks; null lists every agent's.
 * @property conversationId lists only this conversation's tasks; null does not filter by conversation.
 */
data class CronListQuery(val agentId: String?, val conversationId: String?)

/**
 * Client-controlled fields for creating a cron task.
 *
 * @property agentId the agent the task runs as.
 * @property name the task's name.
 * @property description what the task is for.
 * @property prompt the text sent to the agent on each run.
 * @property recurring whether the task repeats; false runs it once.
 * @property cron the cron expression of a repeating task.
 * @property every an interval carried for callers that schedule by interval; it is not sent to the host.
 * @property at when a one-off task runs, sent to the host as `scheduled_for`.
 * @property timezone the zone [cron] is read in.
 * @property conversationId the conversation the task posts into; null lets the host choose.
 */
data class CronAddRequest(
    val agentId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val recurring: Boolean,
    val cron: String?,
    val every: String?,
    val at: String?,
    val timezone: String?,
    val conversationId: String?,
)
