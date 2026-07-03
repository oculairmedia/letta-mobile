package com.letta.mobile.data.controller.node.iroh

object HealthAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminProxyClient(adminBaseUrl, HttpUrlConnectionAdminProxyTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000))
        router.register("health.check") { proxy.get(adminProxyRequest("v1", "health").build()) }
    }
}
