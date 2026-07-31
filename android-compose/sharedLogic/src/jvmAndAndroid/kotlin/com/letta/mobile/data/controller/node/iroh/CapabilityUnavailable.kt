package com.letta.mobile.data.controller.node.iroh

/**
 * Registers admin_rpc methods that return a typed capability-unavailable
 * response instead of dialing a backend (lgns8.9).
 *
 * Used when a bounded adapter service is not injected, or for methods
 * deliberately deprecated over Iroh (e.g. shim stubs that only ever returned
 * empty data). The router's catch path turns the thrown [adminError] into a
 * `success:false` admin_rpc_response, so the client degrades gracefully
 * without the turn/chat failing — never a fall-back dial to lettashim.
 */
internal object CapabilityUnavailable {
    fun register(router: AdminRpcRouter, methods: Set<String>, service: String) {
        methods.forEach { method ->
            router.register(method) {
                adminError("capability_unavailable: '$method' has no injected '$service' service on this controller")
            }
        }
    }

    /**
     * lgns8.9: a PERMANENT denial — the operation has no owner and is not
     * waiting on injection. [reason] must name the evidence (which upstream
     * surface is missing) so the error tells an operator what would unblock it,
     * and so the shim-retirement ceiling doc can be generated from the code.
     *
     * Every denial here is a method that admin-shim itself does NOT implement
     * (it 404s), so denying is parity, not a regression.
     */
    fun denyFailClosed(router: AdminRpcRouter, methods: Set<String>, reason: String) {
        methods.forEach { method ->
            router.register(method) {
                adminError("capability_unavailable: '$method' has no non-shim owner — $reason")
            }
        }
    }
}
