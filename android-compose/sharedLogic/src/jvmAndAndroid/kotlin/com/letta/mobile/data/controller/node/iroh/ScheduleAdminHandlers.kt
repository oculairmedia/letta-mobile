package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ScheduleAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        router.register("schedule.list") { proxy.get("v1", "schedules") }
        router.register("schedule.get") { p -> AdminHandlerSupport.param(p, "schedule_id")?.let { proxy.get("v1", "schedules", it) } ?: adminError("schedule_id required") }
        router.register("schedule.create") { p -> proxy.post("v1", "schedules", body = p?.toString() ?: "{}") }
        router.register("schedule.delete") { p -> AdminHandlerSupport.param(p, "schedule_id")?.let { proxy.delete("v1", "schedules", it) } ?: adminError("schedule_id required") }
        router.register("job.list") { proxy.get("v1", "jobs") }
        router.register("job.get") { p -> AdminHandlerSupport.param(p, "job_id")?.let { proxy.get("v1", "jobs", it) } ?: adminError("job_id required") }
    }
}
