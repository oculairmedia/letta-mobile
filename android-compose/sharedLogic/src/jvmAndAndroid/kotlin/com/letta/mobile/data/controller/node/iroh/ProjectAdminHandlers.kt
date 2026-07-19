package com.letta.mobile.data.controller.node.iroh

/**
 * Project API parity over Iroh admin_rpc.
 *
 * Proxies the existing HTTP project endpoints; this handler owns no separate
 * project store. It exists so iroh:// clients do not trip the raw-HTTP purity
 * choke-point and mark Projects as unsupported.
 */
object ProjectAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))

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
}
