package com.letta.mobile.data.controller.node.iroh

object ModelAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("model.list") { api.get("models") }
        router.register("model.list.embedding") { api.get("models", "embedding") }
        router.register("provider.list") { api.get("providers") }
    }
}
