package com.letta.mobile.data.transport.api

import kotlin.jvm.JvmInline

/** The identity of a task served by the cron transport. */
@JvmInline
value class CronTaskRef(val value: String)

/** The agent whose cron tasks are addressed by an operation. */
@JvmInline
value class CronAgentRef(val value: String)

/** Optional scope for listing cron tasks. */
data class CronListQuery(val agentId: String?, val conversationId: String?)

/** Client-controlled fields for creating a cron task. */
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
