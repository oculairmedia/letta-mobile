package com.letta.mobile.data.controller.node.iroh

/**
 * lgns8.9 disposition for the MCP-catalog and passage-read domains.
 *
 *  - `mcp.list`: admin-shim has no `/v1/mcp/servers` route at all (it stubs
 *    `/v1/mcp-servers` with `json(res, 200, [])`), so the proxy call this
 *    handler used to make already 404'd. letta-code's MCP servers are process
 *    configuration, not local-backend state, and the pinned v2 inventory has no
 *    MCP catalog command. The controller answers with the empty success shape so
 *    the MCP settings screen renders instead of erroring — the wider catalog
 *    surface stays tracked under `unrouted_domains.mcp_catalog`.
 *  - `passage.list`: `/v1/agents/{id}/passages` does not exist in admin-shim
 *    either, and there is no archival-memory store to read, so it fails closed
 *    alongside the passage writes in [ArchiveAdminHandlers].
 */
object McpAdminHandlers {
    fun register(router: AdminRpcRouter) {
        NativeAdminCatalogs.registerEmptyByContract(router, EMPTY_BY_CONTRACT)
        CapabilityUnavailable.denyFailClosed(
            router,
            DENIED,
            reason = "the letta-code local backend has no archival-memory store " +
                "(admin-shim has no /v1/agents/{id}/passages route) and the pinned App Server v2 " +
                "inventory has no passage command; upstream must expose one",
        )
    }

    val EMPTY_BY_CONTRACT: Set<String> = setOf("mcp.list")

    val DENIED: Set<String> = setOf("passage.list")

    val METHODS: Set<String> = EMPTY_BY_CONTRACT + DENIED
}
