package com.letta.mobile.data.controller.node.iroh

/**
 * Project API parity over Iroh admin_rpc (lgns8.9).
 *
 * These 9 methods are owned by the VibeSync product service, not lettashim.
 * The handler talks to VibeSync DIRECTLY through an injected base URL rather
 * than routing through lettashim's `/api` reverse-proxy splice — removing
 * the shim dependency for the project surface (matrix owner: vibesync_service).
 * When no VibeSync service is configured ([vibesyncBaseUrl] == null) the
 * methods return a typed capability-unavailable rather than dialing the shim,
 * so Projects degrade gracefully without failing chat.
 */
object ProjectAdminHandlers {
    fun register(router: AdminRpcRouter, vibesyncBaseUrl: String?) {
        if (vibesyncBaseUrl == null) {
            CapabilityUnavailable.register(router, PROJECT_METHODS, service = "vibesync")
            return
        }
        val api = AdminHandlerSupport(AdminProxyClient(vibesyncBaseUrl))

        router.register("project.list") { params ->
            api.get(AdminPath.api("projects")) {
                query("limit", param(params, AdminParamKey("limit")))
            }
        }

        router.register("project.get") { params ->
            val identifier = params.requireProjectIdentifierParam()
            api.get(AdminPath.api("projects", identifier))
        }

        router.register("project.beadsRemoteStatus") { params ->
            val identifier = params.requireProjectIdentifierParam()
            api.get(AdminPath.api("projects", identifier, "beads-remote"))
        }

        router.register("project.provisionBeadsRemote") { params ->
            val identifier = params.requireProjectIdentifierParam()
            api.post(
                AdminPath.api("projects", identifier, "beads-remote", "provision"),
                body = passthroughBody(params, listOf(AdminParamKey("identifier"), AdminParamKey("project_id"))),
            )
        }

        router.register("project.triggerSync") { params ->
            api.post(AdminPath.api("sync", "trigger"), body = params.toString())
        }

        router.register("project.create") { params ->
            api.post(AdminPath.api("registry", "projects"), body = params.toString())
        }

        router.register("project.update") { params ->
            val identifier = params.requireProjectIdentifierParam()
            api.patch(
                AdminPath.api("registry", "projects", identifier),
                body = passthroughBody(params, listOf(AdminParamKey("identifier"), AdminParamKey("project_id"))),
            )
        }

        router.register("project.archive") { params ->
            val identifier = params.requireProjectIdentifierParam()
            api.patch(
                AdminPath.api("registry", "projects", identifier),
                body = passthroughBody(params, listOf(AdminParamKey("identifier"), AdminParamKey("project_id"))),
            )
        }

        router.register("project.delete") { params ->
            val identifier = params.requireProjectIdentifierParam()
            api.delete(AdminPath.api("registry", "projects", identifier))
        }
    }

    val PROJECT_METHODS: Set<String> = setOf(
        "project.list", "project.get", "project.beadsRemoteStatus", "project.provisionBeadsRemote",
        "project.triggerSync", "project.create", "project.update", "project.archive", "project.delete",
    )
}
