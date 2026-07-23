package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object HealthAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String, controller: AppServerController? = null) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl, HttpUrlConnectionAdminProxyTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)))
        router.register("health.check") {
            // lgns8.8: health reports the CONTROLLER's own truthful readiness
            // (matrix: controller_native) rather than proxying a shim endpoint.
            // Falls back to the shim probe only when no controller is wired.
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
                api.get(AdminPath.v1("health"))
            }
        }
    }
}
