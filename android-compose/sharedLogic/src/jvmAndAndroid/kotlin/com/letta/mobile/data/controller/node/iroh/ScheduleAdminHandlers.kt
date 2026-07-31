package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * lgns8.9: `schedule.*` is served by the NATIVE App Server cron commands.
 *
 * The product "scheduled message" surface was never a store of its own —
 * admin-shim's `/v1/agents/{id}/schedule` routes are a thin compatibility
 * translation over cron tasks (`server.ts:cronTaskToScheduledMessage` /
 * `scheduleCreateParamsToCronBody` over `lib/crons.ts`). The pinned
 * `@letta-ai/letta-code` 0.29.12 inventory exposes those same tasks natively as
 * `cron_list` / `cron_get` / `cron_add` / `cron_delete`, so the translation —
 * and nothing else — moves into the controller and the shim drops out.
 *
 * This is the one admin domain where the WRITES have a native owner: schedule
 * create/delete are `cron_add`/`cron_delete`, executed by the App Server, which
 * is the local backend's single writer. The controller never writes the store.
 *
 * Note `/v1/schedules` (unscoped) never existed in admin-shim — only the
 * agent-scoped routes did. `cron_list` accepts an optional agent filter, so the
 * unscoped list is now genuinely served rather than 404'd.
 */
object ScheduleAdminHandlers {
    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        registerScheduleMethods(router, nativeClient)
        // Jobs are a vanilla-Letta entity the local backend does not model:
        // admin-shim serves `GET /v1/jobs` from `stubList` and has no detail
        // route at all. List answers empty-by-contract; detail fails closed.
        NativeAdminCatalogs.registerEmptyByContract(router, JOB_EMPTY_BY_CONTRACT)
        CapabilityUnavailable.denyFailClosed(
            router,
            JOB_DENIED,
            reason = "the letta-code local backend has no job entity (admin-shim stubs the list " +
                "and has no detail route) and the pinned App Server v2 inventory has no job command; " +
                "upstream must expose one",
        )
    }

    private fun registerScheduleMethods(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        fun requireClient(): AppServerClient =
            nativeClient ?: adminError("capability_unavailable: schedule operations require the native App Server client")

        router.register("schedule.list") { params ->
            val agentId = param(params, AdminParamKey("agent_id"))
            val response = requireClient().cronList(
                AppServerCommand.CronList(requestId = NativeAdmin.requestId(), agentId = agentId),
            )
            if (!response.success) adminError(response.error ?: "cron_list failed")
            val all = (response.tasks ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
            page(all, after = param(params, AdminParamKey("after")), limit = param(params, AdminParamKey("limit"))?.toIntOrNull())
        }

        router.register("schedule.get") { params ->
            val scheduleId = params.requireParam(AdminParamKey("schedule_id"))
            val agentId = param(params, AdminParamKey("agent_id"))
            val response = requireClient().cronGet(
                AppServerCommand.CronGet(requestId = NativeAdmin.requestId(), taskId = scheduleId),
            )
            if (!response.success) adminError(response.error ?: "cron_get failed")
            val task = response.task?.takeIf { response.found } ?: adminError("scheduled message $scheduleId not found")
            // admin-shim scopes the detail route to the agent that owns the task.
            if (agentId != null && task["agent_id"]?.stringOrNull() != agentId) {
                adminError("scheduled message $scheduleId not found")
            }
            cronTaskToScheduledMessage(task)
        }

        router.register("schedule.create") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            val request = scheduleCreateParamsToCronAdd(agentId, params)
            val response = requireClient().cronAdd(request)
            if (!response.success) adminError(response.error ?: "cron_add failed")
            cronTaskToScheduledMessage(response.task ?: adminError("cron_add returned no task"))
        }

        router.register("schedule.delete") { params ->
            val scheduleId = params.requireParam(AdminParamKey("schedule_id"))
            val response = requireClient().cronDelete(
                AppServerCommand.CronDelete(requestId = NativeAdmin.requestId(), taskId = scheduleId),
            )
            if (!response.success) adminError(response.error ?: "cron_delete failed")
            if (!response.found) adminError("scheduled message $scheduleId not found")
            buildJsonObject {
                put("deleted", true)
                put("id", scheduleId)
            }
        }
    }

    /** Port of admin-shim `handleAgentScheduleList` paging + envelope. */
    private fun page(tasks: List<JsonObject>, after: String?, limit: Int?): JsonObject {
        val all = tasks.map { cronTaskToScheduledMessage(it) }
        val start = if (after != null) {
            (all.indexOfFirst { it["id"]?.stringOrNull() == after } + 1).coerceAtLeast(0)
        } else {
            0
        }
        val bounded = limit?.coerceAtLeast(0)
        val windowEnd = if (bounded != null) (start + bounded).coerceAtMost(all.size) else all.size
        val window = if (start >= all.size) emptyList() else all.subList(start, windowEnd)
        return buildJsonObject {
            put("has_next_page", bounded != null && start + bounded < all.size)
            put("scheduled_messages", buildJsonArray { window.forEach { add(it) } })
        }
    }

    /** Port of admin-shim `cronTaskToScheduledMessage`: CronTask -> ScheduledMessage wire shape. */
    internal fun cronTaskToScheduledMessage(task: JsonObject): JsonObject {
        val prompt = task["prompt"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
            ?: task["description"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
            ?: task["name"]?.stringOrNull()
            ?: ""
        val recurring = task["recurring"]?.booleanOrNull() ?: false
        val scheduledFor = task["scheduled_for"]?.stringOrNull()
        val schedule = buildJsonObject {
            if (recurring) {
                put("type", "recurring")
                put("cron_expression", task["cron"]?.stringOrNull() ?: "")
            } else {
                put("type", "one-time")
                // admin-shim: `Date.parse(scheduled_for) / 1000` — epoch SECONDS.
                scheduledFor?.let { iso ->
                    runCatching { Instant.parse(iso).toEpochMilli() / MILLIS_PER_SECOND }
                        .getOrNull()
                        ?.let { put("scheduled_at", it) }
                }
            }
        }
        return buildJsonObject {
            put("id", task["id"]?.stringOrNull() ?: "")
            put("agent_id", task["agent_id"]?.stringOrNull() ?: "")
            put(
                "message",
                buildJsonObject {
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", prompt)
                                },
                            )
                        },
                    )
                    put("callback_url", JsonNull)
                    put("include_return_message_types", JsonArray(emptyList()))
                    put("max_steps", JsonNull)
                },
            )
            put("next_scheduled_time", scheduledFor?.let { JsonPrimitive(it) } ?: JsonNull)
            put("schedule", schedule)
        }
    }

    /** Port of admin-shim `scheduleCreateParamsToCronBody` + `resolveSchedule`, targeting `cron_add`. */
    private fun scheduleCreateParamsToCronAdd(agentId: String, params: JsonObject?): AppServerCommand.CronAdd {
        val schedule = params?.get("schedule") as? JsonObject ?: adminError("schedule is required")
        val content = (params["messages"] as? JsonArray)
            ?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.firstNotNullOfOrNull { it["content"]?.stringOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: adminError("messages[0].content is required")
        val name = content.take(SCHEDULE_NAME_MAX_CHARS).ifEmpty { "schedule" }

        return when (schedule["type"]?.stringOrNull()) {
            "recurring" -> {
                val cron = schedule["cron_expression"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
                    ?: adminError("schedule.cron_expression is required for recurring schedules")
                cronAdd(agentId, name, content, cron, recurring = true, scheduledFor = null)
            }

            "one-time" -> {
                val scheduledAt = schedule["scheduled_at"]?.doubleOrNull()
                    ?: adminError("schedule.scheduled_at is required for one-time schedules")
                val instant = Instant.ofEpochMilli((scheduledAt * MILLIS_PER_SECOND).toLong())
                cronAdd(
                    agentId,
                    name,
                    content,
                    cron = cronExpressionForInstant(instant),
                    recurring = false,
                    scheduledFor = instant.toString(),
                )
            }

            else -> adminError("schedule.type must be recurring or one-time")
        }
    }

    private fun cronAdd(
        agentId: String,
        name: String,
        prompt: String,
        cron: String,
        recurring: Boolean,
        scheduledFor: String?,
    ) = AppServerCommand.CronAdd(
        requestId = NativeAdmin.requestId(),
        agentId = agentId,
        name = name,
        description = CREATED_VIA,
        cron = cron,
        recurring = recurring,
        prompt = prompt,
        scheduledFor = scheduledFor,
    )

    /** Port of admin-shim `cronExpressionForDate` — LOCAL time fields, matching Node's `Date` getters. */
    private fun cronExpressionForInstant(instant: Instant): String {
        val local = instant.atZone(ZoneId.systemDefault())
        return "${local.minute} ${local.hour} ${local.dayOfMonth} ${local.monthValue} *"
    }

    val JOB_EMPTY_BY_CONTRACT: Set<String> = setOf("job.list")

    val JOB_DENIED: Set<String> = setOf("job.get")

    val SCHEDULE_METHODS: Set<String> = setOf(
        "schedule.list",
        "schedule.get",
        "schedule.create",
        "schedule.delete",
    )

    val METHODS: Set<String> = SCHEDULE_METHODS + JOB_EMPTY_BY_CONTRACT + JOB_DENIED

    private const val SCHEDULE_NAME_MAX_CHARS = 80
    private const val MILLIS_PER_SECOND = 1000
    private const val CREATED_VIA = "created via the schedule admin_rpc compatibility route"
}
