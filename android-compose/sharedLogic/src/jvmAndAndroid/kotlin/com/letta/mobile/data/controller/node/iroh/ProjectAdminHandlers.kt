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
            api.get(
                adminProxyRequest("api", "projects")
                    .query("limit", param(params, "limit"))
                    .build(),
            )
        }

        router.register("project.get") { params ->
            val identifier = projectIdentifierParam(params) ?: return@register adminError(PROJECT_IDENTIFIER_REQUIRED)
            api.get(adminProxyRequest("api", "projects", identifier).build())
        }

        router.register("project.beadsRemoteStatus") { params ->
            val identifier = projectIdentifierParam(params) ?: return@register adminError(PROJECT_IDENTIFIER_REQUIRED)
            api.get(adminProxyRequest("api", "projects", identifier, "beads-remote").build())
        }

        router.register("project.provisionBeadsRemote") { params ->
            val identifier = projectIdentifierParam(params) ?: return@register adminError(PROJECT_IDENTIFIER_REQUIRED)
            api.post(
                adminProxyRequest("api", "projects", identifier, "beads-remote", "provision").build(),
                body = passthroughBody(params, "identifier", "project_id"),
            )
        }

        router.register("project.triggerSync") { params ->
            api.post(adminProxyRequest("api", "sync", "trigger").build(), body = params.toString())
        }

        router.register("project.create") { params ->
            api.post(adminProxyRequest("api", "registry", "projects").build(), body = params.toString())
        }

        router.register("project.update") { params ->
            val identifier = projectIdentifierParam(params) ?: return@register adminError(PROJECT_IDENTIFIER_REQUIRED)
            api.patch(
                adminProxyRequest("api", "registry", "projects", identifier).build(),
                body = passthroughBody(params, "identifier", "project_id"),
            )
        }

        router.register("project.archive") { params ->
            val identifier = projectIdentifierParam(params) ?: return@register adminError(PROJECT_IDENTIFIER_REQUIRED)
            api.patch(
                adminProxyRequest("api", "registry", "projects", identifier).build(),
                body = passthroughBody(params, "identifier", "project_id"),
            )
        }

        router.register("project.delete") { params ->
            val identifier = projectIdentifierParam(params) ?: return@register adminError(PROJECT_IDENTIFIER_REQUIRED)
            api.delete(adminProxyRequest("api", "registry", "projects", identifier).build())
        }
    }
}
