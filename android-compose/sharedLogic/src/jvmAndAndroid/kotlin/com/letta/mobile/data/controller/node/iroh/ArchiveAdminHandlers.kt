package com.letta.mobile.data.controller.node.iroh

/**
 * lgns8.9 disposition for the archive/folder/group/passage domains.
 *
 * Evidence from the pinned admin-shim (`server.ts`, 0.29.12 host):
 *  - `GET /v1/archives`, `/v1/folders`, `/v1/groups` are `stubList` routes —
 *    literally `json(res, 200, [])`. The letta-code local backend has no
 *    archive, folder, or group entity at all, so an empty list IS the complete
 *    answer. They are served natively (empty-by-contract) rather than denied,
 *    because denying would regress the admin screens that render them.
 *  - `POST /v1/agents/{id}/archival-memory` and `DELETE .../{pid}` do not exist
 *    in admin-shim: those paths 404. There is no archival-memory store in the
 *    local backend and no native v2 command for one, so passage WRITES fail
 *    closed. That is parity with today, not a regression.
 */
object ArchiveAdminHandlers {
    fun register(router: AdminRpcRouter) {
        NativeAdminCatalogs.registerEmptyByContract(router, EMPTY_BY_CONTRACT)
        CapabilityUnavailable.denyFailClosed(
            router,
            PASSAGE_WRITE_METHODS,
            reason = "the letta-code local backend has no archival-memory store " +
                "(admin-shim 404s /v1/agents/{id}/archival-memory) and the pinned App Server v2 " +
                "inventory has no passage command; upstream must expose one",
        )
    }

    /** Domains the local backend does not model — answered with vanilla's empty-list success shape. */
    val EMPTY_BY_CONTRACT: Set<String> = setOf(
        "archive.list",
        "folder.list",
        "group.list",
    )

    /** Permanently denied: no store, no native command. */
    val PASSAGE_WRITE_METHODS: Set<String> = setOf(
        "passage.create",
        "passage.delete",
    )

    val METHODS: Set<String> = EMPTY_BY_CONTRACT + PASSAGE_WRITE_METHODS
}
