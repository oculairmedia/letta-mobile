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
        router.register("schedule.list") { api.get("schedules") }
        router.register("schedule.get") { p -> param(p, "schedule_id")?.let { api.get("schedules", it) } ?: adminError("schedule_id required") }
        router.register("schedule.create") { p -> api.post("schedules", body = p?.toString() ?: "{}") }
        router.register("schedule.delete") { p -> param(p, "schedule_id")?.let { api.delete("schedules", it) } ?: adminError("schedule_id required") }
        router.register("job.list") { api.get("jobs") }
        router.register("job.get") { p -> param(p, "job_id")?.let { api.get("jobs", it) } ?: adminError("job_id required") }
    }
}
