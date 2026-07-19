package com.letta.mobile.data.repository.iroh

import kotlin.jvm.JvmInline

/** Typed admin_rpc method name (e.g. `project.list`). */
@JvmInline
value class AdminRpcMethod(val value: String) {
    override fun toString(): String = value
}

/** Typed admin_rpc HTTP-equivalent path. */
@JvmInline
value class AdminRpcPath(val value: String) {
    override fun toString(): String = value
}

/**
 * One `admin_rpc` invocation: the registry method plus the HTTP-equivalent
 * path (and optional JSON body) the server-side handler expects — the same
 * conventions the Android IrohAdminRpc*Source classes use.
 */
data class AdminRpcCall(
    val method: String,
    val path: String,
    val body: String? = null,
) {
    companion object {
        fun of(method: AdminRpcMethod, path: AdminRpcPath, body: String? = null): AdminRpcCall =
            AdminRpcCall(method = method.value, path = path.value, body = body)
    }
}
