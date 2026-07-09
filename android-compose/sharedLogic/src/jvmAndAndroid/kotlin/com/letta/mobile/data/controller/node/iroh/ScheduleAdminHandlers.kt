package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ScheduleAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(AdminProxyClient(adminBaseUrl))
        router.register("schedule.list") { api.get("schedules") }
        router.register("schedule.get") { p -> id(p, "schedule_id")?.let { api.get("schedules", it) } ?: adminError("schedule_id required") }
        router.register("schedule.create") { p -> api.post("schedules", body = p?.toString() ?: "{}") }
        router.register("schedule.delete") { p -> id(p, "schedule_id")?.let { api.delete("schedules", it) } ?: adminError("schedule_id required") }
        router.register("job.list") { api.get("jobs") }
        router.register("job.get") { p -> id(p, "job_id")?.let { api.get("jobs", it) } ?: adminError("job_id required") }
    }

    private class Api(private val proxy: AdminProxyClient) {
        fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
        fun post(vararg segments: String, body: String): JsonElement = proxy.post(adminProxyRequest("v1", *segments).build(), body)
        fun delete(vararg segments: String): JsonElement = proxy.delete(adminProxyRequest("v1", *segments).build())
    }

    private fun id(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull

}
