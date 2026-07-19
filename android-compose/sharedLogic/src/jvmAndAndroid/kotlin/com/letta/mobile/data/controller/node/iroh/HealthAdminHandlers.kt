package com.letta.mobile.data.controller.node.iroh

object HealthAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl, HttpUrlConnectionAdminProxyTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)))
        router.register("health.check") { api.get(AdminPath.v1("health")) }
    }
}
