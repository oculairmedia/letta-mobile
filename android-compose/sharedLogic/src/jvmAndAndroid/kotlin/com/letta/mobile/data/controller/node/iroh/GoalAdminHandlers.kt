package com.letta.mobile.data.controller.node.iroh

/**
 * Phase 3: shim-era goal.* surfaces are product-removed. Always return a typed
 * capability denial — never dial LettaShim or a generic admin REST base.
 */
object GoalAdminHandlers {
    fun register(router: AdminRpcRouter, @Suppress("UNUSED_PARAMETER") adminBaseUrl: String? = null) {
        CapabilityUnavailable.register(router, METHODS, service = "admin_rest")
    }

    val METHODS: Set<String> = setOf(
        "goal.get",
        "goal.command",
    )
}
