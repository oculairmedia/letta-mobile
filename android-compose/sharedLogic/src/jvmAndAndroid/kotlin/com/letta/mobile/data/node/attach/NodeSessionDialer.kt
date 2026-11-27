package com.letta.mobile.data.node.attach

import com.letta.mobile.data.controller.extras.relay.AppToolRelay
import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodeCarrier
import com.letta.mobile.data.node.carrier.NodeCarrierWire
import com.letta.mobile.data.node.carrier.NodeCredential
import com.letta.mobile.data.node.carrier.NodeOpenRequest
import com.letta.mobile.data.node.carrier.NodeSession
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.iroh.DirectAppServerDial
import com.letta.mobile.data.transport.iroh.DirectAppServerDialer
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Dials a node through [carrier] at [address] and hands the channel transport each authenticated session as one dial,
 * so the shell attaches to a node in this process with the same client stack, turn engine, admin router relays, feed
 * and pushes it uses for a node over Iroh. Each dial opens a fresh [NodeSession] in a child of [scope]; closing the
 * dial detaches it (turns keep running on the node) and ends that child.
 *
 * The session signs in by its carrier identity when the connect config has no token (the in-process carrier's local
 * shell is admitted by construction), and with the token otherwise.
 */
class NodeSessionDialer(
    private val carrier: NodeCarrier,
    private val address: NodeAddress,
    private val scope: CoroutineScope,
) : DirectAppServerDialer {
    /** The first dial starts the node and its App Server, which takes longer than reaching a running one. */
    override val readyTimeout: Duration get() = NODE_READY_TIMEOUT

    override suspend fun dial(config: IrohConnectConfig): DirectAppServerDial {
        @Suppress("NoDetachedCoroutineLifecycle") // A child of the owner's scope, cancelled when the dial closes or fails.
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        return runCatching { open(config, sessionScope) }
            .onFailure { sessionScope.cancel() }
            .getOrThrow()
    }

    private suspend fun open(config: IrohConnectConfig, sessionScope: CoroutineScope): DirectAppServerDial {
        val session = NodeSession.open(carrier, openRequest(config), sessionScope)
        val transport = NodeSessionAppServerTransport(session, sessionScope)
        return DirectAppServerDial(
            client = DefaultAppServerClient(transport, parentScope = sessionScope),
            connectionState = transport.connectionState,
            streamFrames = transport.streamFrames,
            hostCapabilities = session.capabilities.host,
            adminRpc = transport::adminRpc,
            close = {
                session.close()
                sessionScope.cancel()
            },
            adminRpcStream = transport::adminRpcStream,
        )
    }

    private fun openRequest(config: IrohConnectConfig) = NodeOpenRequest(
        address = address,
        credential = config.token.takeIf { it.isNotBlank() }?.let(NodeCredential::Token) ?: NodeCredential.CarrierIdentity,
        peerName = config.deviceId.takeIf { it.isNotBlank() },
        clientCapabilities = SHELL_CAPABILITIES,
    )

    private companion object {
        val NODE_READY_TIMEOUT: Duration = 90.seconds

        /** What the shell's client stack advertises to any node, as its Iroh dial does. */
        val SHELL_CAPABILITIES = setOf(
            NodeCarrierWire.FRAME_PART_CAPABILITY,
            ServerFrame.ConversationActivity.CAPABILITY,
            AppToolRelay.CAPABILITY,
        )
    }
}
