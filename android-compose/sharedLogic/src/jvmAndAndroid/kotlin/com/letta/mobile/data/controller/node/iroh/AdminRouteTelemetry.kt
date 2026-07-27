package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry

/**
 * Route-selection telemetry for Iroh `admin_rpc` (runbook Phase 1).
 *
 * Records which owner/tier handled a request without logging bodies, tokens,
 * prompts, memory contents, or provider credentials. Values are short enums
 * suitable for soak dashboards and shim-off gates.
 */
internal object AdminRouteTelemetry {
    const val CATEGORY = "IrohAdminRoute"

    fun selected(
        method: String,
        owner: String,
        route: String,
        outcome: String,
        reason: String? = null,
    ) {
        if (reason == null) {
            Telemetry.event(
                CATEGORY,
                "selected",
                "method" to method,
                "owner" to owner,
                "route" to route,
                "outcome" to outcome,
            )
        } else {
            Telemetry.event(
                CATEGORY,
                "selected",
                "method" to method,
                "owner" to owner,
                "route" to route,
                "outcome" to outcome,
                "reason" to reason,
            )
        }
    }

    fun fallback(
        method: String,
        fromRoute: String,
        toRoute: String,
        reason: String,
    ) {
        Telemetry.event(
            CATEGORY,
            "fallback",
            "method" to method,
            "from" to fromRoute,
            "to" to toRoute,
            "reason" to reason,
        )
    }
}
