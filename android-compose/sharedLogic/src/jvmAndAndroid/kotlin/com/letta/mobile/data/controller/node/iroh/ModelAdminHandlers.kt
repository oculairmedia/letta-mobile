package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AppServerListModelsAdapter
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray

/**
 * Model + provider catalogs.
 *
 * `model.list` is native (`list_models`). lgns8.9 dispositions the other two:
 * admin-shim served `GET /v1/models/embedding` and `GET /v1/providers` from
 * HARD-CODED constants (a single `text-embedding-3-small` descriptor and a
 * single `lmstudio-local` BYOK provider built from `LMSTUDIO_BASE_URL`) — no
 * datastore, no upstream call. They are therefore controller-native constants
 * here, not a bounded REST adapter. The pinned v2 `list_connect_providers`
 * command is the App Server's connect-provider domain, NOT the Letta
 * `/v1/providers` catalog, and the matrix forbids conflating them.
 */
object ModelAdminHandlers {
    fun register(router: AdminRpcRouter, nativeClient: AppServerClient? = null, lmstudioBaseUrl: String = DEFAULT_LMSTUDIO_BASE_URL) {
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
        router.register("model.list.embedding") { NativeAdminCatalogs.embeddingModelCatalog() }
        router.register("provider.list") { NativeAdminCatalogs.providerCatalog(lmstudioBaseUrl) }
    }

    /** Mirrors admin-shim's `process.env.LMSTUDIO_BASE_URL || "http://localhost:8082/v1"`. */
    val DEFAULT_LMSTUDIO_BASE_URL: String =
        System.getenv("LMSTUDIO_BASE_URL")?.takeIf { it.isNotBlank() } ?: "http://localhost:8082/v1"

    /** Constant catalogs owned by the controller (no datastore, no shim). */
    val CONSTANT_CATALOG_METHODS: Set<String> = setOf("model.list.embedding", "provider.list")

    val METHODS: Set<String> = setOf("model.list") + CONSTANT_CATALOG_METHODS
}
