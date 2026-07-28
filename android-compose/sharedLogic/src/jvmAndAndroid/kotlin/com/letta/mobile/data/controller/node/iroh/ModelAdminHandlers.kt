package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AppServerListModelsAdapter
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray

object ModelAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String?, nativeClient: AppServerClient? = null) {
        val api = adminBaseUrl?.let { AdminHandlerSupport(AdminProxyClient(it)) }
        router.register("model.list") { params ->
            // Phase 2: native list_models is the only Letta-owned source. Legacy
            // shim catalog shape is no longer the default; callers that still need
            // the old REST catalog must wait for a bounded non-shim owner (Phase 3).
            NativeAdmin.require(nativeClient, NativeAdminOp.ModelList) { c ->
                val response = c.listModels(
                    AppServerCommand.ListModels(
                        requestId = NativeAdmin.requestId(),
                        force = param(params, AdminParamKey("force"))?.toBooleanStrictOrNull(),
                    ),
                )
                if (!response.success) {
                    null
                } else {
                    AppServerListModelsAdapter.toLlmModelArray(
                        response.entries ?: JsonArray(emptyList()),
                    )
                }
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
