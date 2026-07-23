package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Native cron scheduling over admin_rpc (lgns8.8). These methods are
 * NATIVE-ONLY: the Iroh transport never had a cron surface (the legacy
 * mobile-WS path retires with the shim in lgns8.11), so there is no proxy
 * fallback — without a live App Server client they fail with a clear error
 * instead of pretending.
 */
internal object CronAdminHandlers {
    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        fun requireClient(): AppServerClient =
            nativeClient ?: adminError("cron operations require the native App Server client")

        router.register("cron.list") { params ->
            val response = requireClient().cronList(
                AppServerCommand.CronList(
                    requestId = NativeAdmin.requestId(),
                    agentId = param(params, AdminParamKey("agent_id")),
                    conversationId = param(params, AdminParamKey("conversation_id")),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_list failed")
            buildJsonObject { put("tasks", response.tasks ?: JsonArray(emptyList())) }
        }
        router.register("cron.add") { params ->
            val response = requireClient().cronAdd(
                AppServerCommand.CronAdd(
                    requestId = NativeAdmin.requestId(),
                    agentId = params.requireParam(AdminParamKey("agent_id")),
                    conversationId = param(params, AdminParamKey("conversation_id")),
                    name = params.requireParam(AdminParamKey("name")),
                    description = param(params, AdminParamKey("description")) ?: "",
                    cron = params.requireParam(AdminParamKey("cron")),
                    timezone = param(params, AdminParamKey("timezone")),
                    recurring = param(params, AdminParamKey("recurring"))?.toBooleanStrictOrNull() ?: true,
                    prompt = params.requireParam(AdminParamKey("prompt")),
                    scheduledFor = param(params, AdminParamKey("scheduled_for")),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_add failed")
            buildJsonObject {
                put("task", response.task ?: JsonNull)
                response.warning?.let { put("warning", it) }
            }
        }
        router.register("cron.get") { params ->
            val response = requireClient().cronGet(
                AppServerCommand.CronGet(
                    requestId = NativeAdmin.requestId(),
                    taskId = params.requireParam(AdminParamKey("task_id")),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_get failed")
            buildJsonObject {
                put("found", response.found)
                put("task", response.task ?: JsonNull)
            }
        }
        router.register("cron.runs") { params ->
            val response = requireClient().cronRuns(
                AppServerCommand.CronRuns(
                    requestId = NativeAdmin.requestId(),
                    taskId = params.requireParam(AdminParamKey("task_id")),
                    limit = param(params, AdminParamKey("limit"))?.toIntOrNull(),
                    offset = param(params, AdminParamKey("offset"))?.toIntOrNull(),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_runs failed")
            buildJsonObject { put("page", response.page ?: JsonNull) }
        }
        router.register("cron.trigger") { params ->
            val response = requireClient().cronTrigger(
                AppServerCommand.CronTrigger(
                    requestId = NativeAdmin.requestId(),
                    taskId = params.requireParam(AdminParamKey("task_id")),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_trigger failed")
            buildJsonObject {
                put("found", response.found)
                put("task", response.task ?: JsonNull)
            }
        }
        router.register("cron.update") { params ->
            val response = requireClient().cronUpdate(
                AppServerCommand.CronUpdate(
                    requestId = NativeAdmin.requestId(),
                    taskId = params.requireParam(AdminParamKey("task_id")),
                    name = param(params, AdminParamKey("name")),
                    description = param(params, AdminParamKey("description")),
                    conversationId = param(params, AdminParamKey("conversation_id")),
                    cron = param(params, AdminParamKey("cron")),
                    timezone = param(params, AdminParamKey("timezone")),
                    recurring = param(params, AdminParamKey("recurring"))?.toBooleanStrictOrNull(),
                    prompt = param(params, AdminParamKey("prompt")),
                    scheduledFor = param(params, AdminParamKey("scheduled_for")),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_update failed")
            buildJsonObject { put("task", response.task ?: JsonNull) }
        }
        router.register("cron.delete") { params ->
            val response = requireClient().cronDelete(
                AppServerCommand.CronDelete(
                    requestId = NativeAdmin.requestId(),
                    taskId = params.requireParam(AdminParamKey("task_id")),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_delete failed")
            buildJsonObject { put("found", response.found) }
        }
        router.register("cron.delete_all") { params ->
            val response = requireClient().cronDeleteAll(
                AppServerCommand.CronDeleteAll(
                    requestId = NativeAdmin.requestId(),
                    agentId = params.requireParam(AdminParamKey("agent_id")),
                ),
            )
            if (!response.success) adminError(response.error ?: "cron_delete_all failed")
            buildJsonObject { put("deleted", response.deleted) }
        }
    }

    val methods: Set<String> = setOf(
        "cron.list", "cron.add", "cron.get", "cron.runs",
        "cron.trigger", "cron.update", "cron.delete", "cron.delete_all",
    )
}
