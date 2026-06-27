package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IrohNodeEndpoint(
    private val alpn: ByteArray = DEFAULT_ALPN,
    private val scope: CoroutineScope,
) {
    private var endpoint: Endpoint? = null
    private var acceptJob: Job? = null

    fun nodeIdHex(): String {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        val addr = ep.addr()
        return addr.toString()
    }

    fun asAppServerEndpoint(): AppServerEndpoint {
        return AppServerEndpoint(
            scheme = "iroh",
            address = nodeIdHex(),
            bearerToken = null,
        )
    }

    suspend fun create() {
        require(endpoint == null) { "IrohNodeEndpoint already created" }
        endpoint = Endpoint.bind(EndpointOptions(alpns = listOf(alpn)))
    }

    fun start(controller: AppServerController) {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        require(acceptJob == null) { "IrohNodeEndpoint already started" }

        acceptJob = scope.launch {
            while (isActive) {
                try {
                    val incoming = ep.acceptNext() ?: break
                    val accepting = incoming.accept()
                    val connection = accepting.connect()

                    launch {
                        IrohNodeConnection(connection, controller, alpn).serve()
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    suspend fun shutdown() {
        acceptJob?.cancel()
        acceptJob = null

        endpoint?.let {
            runCatching { it.shutdown() }
            runCatching { it.close() }
        }
        endpoint = null
    }

    companion object {
        val DEFAULT_ALPN = "/letta/appserver/0".toByteArray()
    }
}
