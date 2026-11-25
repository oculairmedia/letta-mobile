package com.letta.mobile.data.node

import com.letta.mobile.data.transport.appserver.applyAppServerDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * The JVM's App Server HTTP clients: the CIO engine with the App Server frame limits and
 * permessage-deflate. A client with a request timeout also bounds its connect at 30 seconds and its
 * socket at the request timeout.
 */
object JvmAppServerHttpClients : AppServerHttpClients {
    private const val CONNECT_TIMEOUT_MS = 30_000L

    override fun create(requestTimeoutMs: Long?): HttpClient = HttpClient(CIO) {
        install(WebSockets) { applyAppServerDefaults() }
        if (requestTimeoutMs != null) {
            install(HttpTimeout) {
                this.requestTimeoutMillis = requestTimeoutMs
                this.connectTimeoutMillis = CONNECT_TIMEOUT_MS
                this.socketTimeoutMillis = requestTimeoutMs
            }
        }
    }
}
