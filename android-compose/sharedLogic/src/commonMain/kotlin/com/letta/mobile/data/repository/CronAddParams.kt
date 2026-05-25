package com.letta.mobile.data.repository

/**
 * Client-controlled subset of a scheduled cron task.
 *
 * Server-assigned fields (`id`, `status`, `created_at`, `fire_count`, etc.)
 * are filled in by the shim and returned on the `cron_add_response`.
 *
 * Exactly one of [cron] / [every] / [at] should be non-null. The shim
 * normalizes all three into the persisted task's `cron` field.
 */
data class CronAddParams(
    val agentId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val recurring: Boolean,
    val cron: String? = null,
    val every: String? = null,
    val at: String? = null,
    val timezone: String? = null,
    val conversationId: String? = null,
)
