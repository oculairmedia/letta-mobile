package com.letta.mobile.data.controller.node.iroh

object RunAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
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
}
