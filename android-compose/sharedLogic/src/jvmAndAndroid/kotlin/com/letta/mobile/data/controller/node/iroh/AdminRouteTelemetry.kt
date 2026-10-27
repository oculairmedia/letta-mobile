package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.telemetryAttributes

import com.letta.mobile.util.attr
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

    data class Selection(
        val method: String,
        val owner: String,
        val route: String,
        val outcome: String,
        val reason: String? = null,
    )

    data class Fallback(
        val method: String,
        val fromRoute: String,
        val toRoute: String,
        val reason: String,
    )

    fun selected(selection: Selection) {
        val fields = mutableListOf(
            "method" to selection.method,
            "owner" to selection.owner,
            "route" to selection.route,
            "outcome" to selection.outcome,
        )
        selection.reason?.let { fields += "reason" to it }
        Telemetry.event(
            CATEGORY,
            "selected",
            attrs = fields.toTypedArray().telemetryAttributes(),
        )
    }

    fun fallback(fallback: Fallback) {
        Telemetry.event(
            CATEGORY,
            "fallback",
            "method" attr fallback.method,
            "from" attr fallback.fromRoute,
            "to" attr fallback.toRoute,
            "reason" attr fallback.reason,
        )
    }
}
