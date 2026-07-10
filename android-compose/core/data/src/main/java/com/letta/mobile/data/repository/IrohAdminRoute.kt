package com.letta.mobile.data.repository

suspend inline fun <reified T> irohAdminRoute(
    client: IrohAdminRpcClient?,
    method: String,
    path: String,
    body: String? = null,
    crossinline httpFallback: suspend () -> T
): T = if (client != null && client.shouldUseIroh()) {
    client.call<T>(method, path, body)
} else {
    httpFallback()
}

suspend inline fun <reified T> irohAdminRouteList(
    client: IrohAdminRpcClient?,
    method: String,
    path: String,
    body: String? = null,
    crossinline httpFallback: suspend () -> List<T>
): List<T> = if (client != null && client.shouldUseIroh()) {
    client.callList<T>(method, path, body)
} else {
    httpFallback()
}

suspend inline fun irohAdminRouteUnit(
    client: IrohAdminRpcClient?,
    method: String,
    path: String,
    body: String? = null,
    crossinline httpFallback: suspend () -> Unit
) {
    if (client != null && client.shouldUseIroh()) {
        client.callUnit(method, path, body)
    } else {
        httpFallback()
    }
}
