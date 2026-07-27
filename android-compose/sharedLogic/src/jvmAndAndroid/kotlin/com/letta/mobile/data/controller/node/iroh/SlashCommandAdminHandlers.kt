package com.letta.mobile.data.controller.node.iroh

/**
 * Phase 3: shim-era slash_command.* surfaces are product-removed. Always return
 * a typed capability denial — never dial LettaShim or a generic admin REST base.
 * Composer slash autocomplete must use a non-shim owner when reintroduced.
 */
object SlashCommandAdminHandlers {
    fun register(router: AdminRpcRouter, @Suppress("UNUSED_PARAMETER") adminBaseUrl: String? = null) {
        CapabilityUnavailable.register(router, METHODS, service = "admin_rest")
    }

    val METHODS: Set<String> = setOf(
        "slash_command.list",
        "slash_command.list_agent",
    )
}
