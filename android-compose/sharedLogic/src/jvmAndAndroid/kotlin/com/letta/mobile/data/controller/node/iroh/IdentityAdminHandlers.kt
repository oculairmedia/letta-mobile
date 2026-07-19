package com.letta.mobile.data.controller.node.iroh

object IdentityAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("identity.list") { api.get(AdminPath.v1("identities")) }
        router.register("identity.get") { p ->
            val identityId = p.requireParam("identity_id")
            api.get(AdminPath.v1("identities", identityId))
        }
    }
}
