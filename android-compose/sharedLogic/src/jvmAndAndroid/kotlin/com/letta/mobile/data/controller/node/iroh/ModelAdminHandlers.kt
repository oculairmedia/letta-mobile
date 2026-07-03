package com.letta.mobile.data.controller.node.iroh

object ModelAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminProxyClient(adminBaseUrl)
        router.register("model.list") { proxy.get(adminProxyRequest("v1", "models").build()) }
        router.register("provider.list") { proxy.get(adminProxyRequest("v1", "providers").build()) }
    }
}
