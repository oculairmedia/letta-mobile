package com.letta.mobile.data.controller.node.iroh

object RunAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String?) {
        // lgns8.9: no admin-rest service injected -> capability-unavailable
        // (never a shim dial). Bounded admin adapter degrades gracefully.
        if (adminBaseUrl == null) {
            CapabilityUnavailable.register(router, METHODS, service = "admin_rest")
            return
        }
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("run.list") { api.get(AdminPath.v1("runs")) }
        router.register("run.get") { params ->
            val runId = params.requireParam(AdminParamKey("run_id"))
            api.get(AdminPath.v1("runs", runId))
        }
        router.register("step.list") { params ->
            val runId = params.requireParam(AdminParamKey("run_id"))
            api.get(AdminPath.v1("runs", runId, "steps"))
        }
    }
    val METHODS: Set<String> = setOf(
        "run.list",
        "run.get",
        "step.list",
    )
}
