package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray

object ModelAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String, nativeClient: AppServerClient? = null) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("model.list") { params ->
            // lgns8.8: native list_models entries have a DIFFERENT wire shape
            // than the shim /v1/models catalog, so the native path is opt-in
            // (native=true) until the lgns8.10 parity gate teaches clients the
            // native shape; the default stays the shim catalog.
            val wantsNative = param(params, AdminParamKey("native")) == "true"
            if (wantsNative) {
                NativeAdmin.attempt(nativeClient, "model.list") { c ->
                    val response = c.listModels(
                        AppServerCommand.ListModels(
                            requestId = NativeAdmin.requestId(),
                            force = param(params, AdminParamKey("force"))?.toBooleanStrictOrNull(),
                        ),
                    )
                    if (response.success) response.entries ?: JsonArray(emptyList()) else null
                } ?: api.get(AdminPath.v1("models"))
            } else {
                api.get(AdminPath.v1("models"))
            }
        }
        router.register("model.list.embedding") { api.get(AdminPath.v1("models", "embedding")) }
        router.register("provider.list") { api.get(AdminPath.v1("providers")) }
    }
}
