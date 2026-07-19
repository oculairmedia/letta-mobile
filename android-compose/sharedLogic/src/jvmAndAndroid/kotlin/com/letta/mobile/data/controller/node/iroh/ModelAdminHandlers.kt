package com.letta.mobile.data.controller.node.iroh

object ModelAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("model.list") { api.get(AdminPath.v1("models")) }
        router.register("model.list.embedding") { api.get(AdminPath.v1("models", "embedding")) }
        router.register("provider.list") { api.get(AdminPath.v1("providers")) }
    }
}
