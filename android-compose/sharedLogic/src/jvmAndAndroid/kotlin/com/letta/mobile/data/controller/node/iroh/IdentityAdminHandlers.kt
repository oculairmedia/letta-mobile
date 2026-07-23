package com.letta.mobile.data.controller.node.iroh

object IdentityAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String?) {
        // lgns8.9: no admin-rest service injected -> capability-unavailable
        // (never a shim dial). Bounded admin adapter degrades gracefully.
        if (adminBaseUrl == null) {
            CapabilityUnavailable.register(router, METHODS, service = "admin_rest")
            return
        }
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("identity.list") { api.get(AdminPath.v1("identities")) }
        router.register("identity.get") { p ->
            val identityId = p.requireParam(AdminParamKey("identity_id"))
            api.get(AdminPath.v1("identities", identityId))
        }
    }
    val METHODS: Set<String> = setOf(
        "identity.list",
        "identity.get",
    )
}
