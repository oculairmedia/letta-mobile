package com.letta.mobile.data.controller.node.iroh

object ScheduleAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String?) {
        // lgns8.9: no admin-rest service injected -> capability-unavailable
        // (never a shim dial). Bounded admin adapter degrades gracefully.
        if (adminBaseUrl == null) {
            CapabilityUnavailable.register(router, METHODS, service = "admin_rest")
            return
        }
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("schedule.list") { p ->
            val agentId = param(p, AdminParamKey("agent_id"))
            if (agentId != null) api.get(AdminPath.v1("agents", agentId, "schedule")) else api.get(AdminPath.v1("schedules"))
        }
        router.register("schedule.get") { p ->
            val scheduleId = p.requireParam(AdminParamKey("schedule_id"))
            val agentId = param(p, AdminParamKey("agent_id"))
            if (agentId != null) api.get(AdminPath.v1("agents", agentId, "schedule", scheduleId)) else api.get(AdminPath.v1("schedules", scheduleId))
        }
        router.register("schedule.create") { p ->
            val agentId = param(p, AdminParamKey("agent_id"))
            if (agentId != null) api.post(AdminPath.v1("agents", agentId, "schedule"), body = p?.toString() ?: "{}") else api.post(AdminPath.v1("schedules"), body = p?.toString() ?: "{}")
        }
        router.register("schedule.delete") { p ->
            val scheduleId = p.requireParam(AdminParamKey("schedule_id"))
            val agentId = param(p, AdminParamKey("agent_id"))
            if (agentId != null) api.delete(AdminPath.v1("agents", agentId, "schedule", scheduleId)) else api.delete(AdminPath.v1("schedules", scheduleId))
        }
        router.register("job.list") { api.get(AdminPath.v1("jobs")) }
        router.register("job.get") { params ->
            val jobId = params.requireParam(AdminParamKey("job_id"))
            api.get(AdminPath.v1("jobs", jobId))
        }
    }
    val METHODS: Set<String> = setOf(
        "schedule.list",
        "schedule.get",
        "schedule.create",
        "schedule.delete",
        "job.list",
        "job.get",
    )
}
