package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonObject

/** Test-only convenience wrapper around [AdminRpcInvocation]. */
internal suspend fun AdminRpcRouter.dispatch(
    requestId: String,
    method: String,
    params: JsonObject?,
    context: AdminRpcRequestContext = AdminRpcRequestContext.Authenticated,
): String = dispatch(AdminRpcInvocation(requestId, method, params, context))
