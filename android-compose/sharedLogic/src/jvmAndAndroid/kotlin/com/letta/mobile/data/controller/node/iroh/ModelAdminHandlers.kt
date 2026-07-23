package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray

object ModelAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String?, nativeClient: AppServerClient? = null) {
        val api = adminBaseUrl?.let { AdminHandlerSupport(AdminProxyClient(it)) }
        router.register("model.list") { params ->
            // lgns8.8: native list_models entries have a DIFFERENT wire shape
            // than the shim /v1/models catalog, so the native path is opt-in
            // (native=true); the default stays the shim catalog. lgns8.9: with
            // no admin-rest service the shim fallback is capability-unavailable.
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
                } ?: (api?.get(AdminPath.v1("models")) ?: adminError("capability_unavailable: model.list has no admin_rest service"))
            } else {
                api?.get(AdminPath.v1("models")) ?: adminError("capability_unavailable: model.list has no admin_rest service")
            }
        }
        if (api == null) {
            CapabilityUnavailable.register(router, setOf("model.list.embedding", "provider.list"), service = "admin_rest")
        } else {
            router.register("model.list.embedding") { api.get(AdminPath.v1("models", "embedding")) }
            router.register("provider.list") { api.get(AdminPath.v1("providers")) }
        }
    }

    val METHODS: Set<String> = setOf("model.list", "model.list.embedding", "provider.list")
}
