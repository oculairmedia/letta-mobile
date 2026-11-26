package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodePeerAcceptor
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Binds a relay-less Iroh endpoint for a loopback test, optionally answering [alpns]. */
internal suspend fun bindLoopbackEndpoint(alpns: List<ByteArray> = emptyList()): Endpoint =
    Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled(), alpns = alpns))

/** Shuts [endpoint] down and releases it, ignoring failures. */
internal suspend fun Endpoint.release() {
    runCatching { shutdown() }
    runCatching { close() }
}

/** The short dial form of [endpoint] (`<node id hex>@host:port,...`), preferring loopback sockets. */
internal suspend fun shortAddress(endpoint: Endpoint, nodeHex: String = IrohDiagnostics.endpointIdHex(endpoint.id())): NodeAddress {
    val sockets = withTimeout(10.seconds) {
        var direct = endpoint.addr().directAddresses()
        while (direct.isEmpty()) {
            delay(50.milliseconds)
            direct = endpoint.addr().directAddresses()
        }
        direct
    }
    val loopback = sockets.filter { it.startsWith("127.") || it.startsWith("[::1]") }
    return NodeAddress("$nodeHex@${loopback.ifEmpty { sockets }.joinToString(",")}")
}

/**
 * A node end of the Iroh carrier on a loopback endpoint: accepts every App Server ALPN connection and serves it to
 * [acceptor] through [serveIrohNodePeer], as a node's accept loop would.
 */
internal class IrohLoopbackNode private constructor(
    val endpoint: Endpoint,
    private val acceptLoop: Job,
) {
    /** Where the carrier dials this node. */
    suspend fun address(): NodeAddress = shortAddress(endpoint)

    /** A well-formed address at this node's sockets naming a node id nobody binds: the dial fails the handshake. */
    suspend fun addressOfAnotherNode(): NodeAddress {
        val stranger = bindLoopbackEndpoint()
        val strangerHex = IrohDiagnostics.endpointIdHex(stranger.addr().id())
        stranger.release()
        return shortAddress(endpoint, strangerHex)
    }

    /** Stops accepting and releases the endpoint. */
    suspend fun stop() {
        acceptLoop.cancel()
        endpoint.release()
    }

    companion object {
        /** Binds the node and starts its accept loop in [scope]. */
        suspend fun start(acceptor: NodePeerAcceptor, scope: CoroutineScope): IrohLoopbackNode {
            val endpoint = bindLoopbackEndpoint(listOf(IrohAppServerTransportAdapter.DEFAULT_ALPN))
            val loop = scope.launch {
                while (isActive) {
                    val incoming = runCatching { endpoint.acceptNext() }.getOrNull() ?: break
                    launch {
                        try {
                            val connection = incoming.accept().connect()
                            serveIrohNodePeer(connection, acceptor)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // A failed handshake or a link that ended badly: the suite checks what the node saw.
                        }
                    }
                }
            }
            return IrohLoopbackNode(endpoint, loop)
        }
    }
}
