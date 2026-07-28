package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 4: health reports only the controller's readiness. There is no
 * LettaShim `/v1/health` fallback — missing controller is a native degraded
 * result so production never dials port 8291 for health.
 */
object HealthAdminHandlers {
    fun register(router: AdminRpcRouter, controller: AppServerController? = null) {
        router.register("health.check") {
            val state = (controller?.state as? StateFlow<AppServerControllerState>)?.value
            if (state != null) {
                buildJsonObject {
                    put("status", if (state is AppServerControllerState.Connected) "ok" else "degraded")
                    put(
                        "controller_state",
                        when (state) {
                            is AppServerControllerState.Connected -> "connected"
                            is AppServerControllerState.Disconnected -> "disconnected"
                            is AppServerControllerState.Error -> "error"
                        },
                    )
                    put("native", true)
                }
            } else {
                AdminRouteTelemetry.selected(
                    AdminRouteTelemetry.Selection(
                        method = "health.check",
                        owner = "controller_native",
                        route = "controller_native",
                        outcome = "unavailable",
                        reason = "no_controller",
                    ),
                )
                buildJsonObject {
                    put("status", "degraded")
                    put("controller_state", "unavailable")
                    put("native", true)
                }
            }
        }
    }
}
