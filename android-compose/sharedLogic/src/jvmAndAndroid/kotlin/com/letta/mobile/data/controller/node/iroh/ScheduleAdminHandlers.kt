package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ScheduleAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("schedule.list") { p ->
            val agentId = param(p, "agent_id")
            if (agentId != null) api.get("agents", agentId, "schedule") else api.get("schedules")
        }
        router.register("schedule.get") { p ->
            val scheduleId = p.requireParam("schedule_id")
            val agentId = param(p, "agent_id")
            if (agentId != null) api.get("agents", agentId, "schedule", scheduleId) else api.get("schedules", scheduleId)
        }
        router.register("schedule.create") { p ->
            val agentId = param(p, "agent_id")
            if (agentId != null) api.post("agents", agentId, "schedule", body = p?.toString() ?: "{}") else api.post("schedules", body = p?.toString() ?: "{}")
        }
        router.register("schedule.delete") { p ->
            val scheduleId = p.requireParam("schedule_id")
            val agentId = param(p, "agent_id")
            if (agentId != null) api.delete("agents", agentId, "schedule", scheduleId) else api.delete("schedules", scheduleId)
        }
        router.register("job.list") { api.get("jobs") }
        router.register("job.get") { p -> param(p, "job_id")?.let { api.get("jobs", it) } ?: adminError("job_id required") }
    }
}
