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
        val irohAppServerEndpoint = irohEndpoint.asAppServerEndpoint()
        return baseIdentity.copy(
            endpoints = baseIdentity.endpoints + irohAppServerEndpoint,
        )
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
            val baseIdentity = NodeIdentity(
                id = id,
                displayName = displayName,
                endpoints = emptySet(),
            )

            val irohEndpoint = IrohNodeEndpoint(alpn, scope)
            irohEndpoint.create()

            IrohNodeServer(baseIdentity, controller, irohEndpoint)
        }
    }
}
