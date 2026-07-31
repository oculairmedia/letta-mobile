package com.letta.mobile.data.controller.node.iroh

/**
 * lgns8.9 disposition for the identity domain.
 *
 * admin-shim serves `GET /v1/identities` from `stubList` (`json(res, 200, [])`):
 * the letta-code local backend has no identity entity. The list is therefore
 * answered natively with the empty success shape, while `GET /v1/identities/{id}`
 * — a route admin-shim never registered, so it 404s — fails closed. The pinned
 * App Server v2 inventory has no identity command either.
 */
object IdentityAdminHandlers {
    fun register(router: AdminRpcRouter) {
        NativeAdminCatalogs.registerEmptyByContract(router, EMPTY_BY_CONTRACT)
        CapabilityUnavailable.denyFailClosed(
            router,
            DENIED,
            reason = "the letta-code local backend has no identity entity " +
                "(admin-shim stubs the list and 404s the detail route) and the pinned App Server v2 " +
                "inventory has no identity command; upstream must expose one",
        )
    }

    val EMPTY_BY_CONTRACT: Set<String> = setOf("identity.list")

    val DENIED: Set<String> = setOf("identity.get")

    val METHODS: Set<String> = EMPTY_BY_CONTRACT + DENIED
}
