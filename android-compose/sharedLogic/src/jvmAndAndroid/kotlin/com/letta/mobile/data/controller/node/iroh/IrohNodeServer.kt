package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.node.NodeIdentity
import com.letta.mobile.data.controller.node.NodeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

class IrohNodeServer(
    private val baseIdentity: NodeIdentity,
    private val controller: AppServerController,
    private val irohEndpoint: IrohNodeEndpoint,
) : NodeServer {
    private var started = false

    override suspend fun advertise(): NodeIdentity {
        // The base identity already includes the iroh endpoint,
        // so just return it as-is
        return baseIdentity
    }

    override suspend fun hostedRuntimes(): List<CanonicalRuntime> {
        return emptyList()
    }

    override suspend fun acceptConnections(port: Int) {
        if (started) return
        irohEndpoint.start(controller)
        started = true
    }

    suspend fun shutdown() {
        irohEndpoint.shutdown()
        started = false
    }

    companion object {
        fun create(
            id: String,
            displayName: String,
            controller: AppServerController,
            scope: CoroutineScope,
            alpn: ByteArray = IrohNodeEndpoint.DEFAULT_ALPN,
        ): IrohNodeServer = runBlocking {
            // Create the iroh endpoint first
            val irohEndpoint = IrohNodeEndpoint(alpn, scope)
            irohEndpoint.create()

            // Create the base identity with the iroh endpoint
            val baseIdentity = NodeIdentity(
                id = id,
                displayName = displayName,
                endpoints = setOf(irohEndpoint.asAppServerEndpoint()),
            )

            IrohNodeServer(baseIdentity, controller, irohEndpoint)
        }
    }
}
