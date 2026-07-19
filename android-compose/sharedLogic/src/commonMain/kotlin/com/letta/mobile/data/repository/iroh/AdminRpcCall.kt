package com.letta.mobile.data.repository.iroh

/**
 * One `admin_rpc` invocation: the registry method plus the HTTP-equivalent
 * path (and optional JSON body) the server-side handler expects — the same
 * conventions the Android IrohAdminRpc*Source classes use.
 */
data class AdminRpcCall(
    val method: String,
    val path: String,
    val body: String? = null,
)
