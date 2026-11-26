package com.letta.mobile.data.node

import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.recordConnectionClosed
import com.letta.mobile.data.node.carrier.iroh.serveIrohNodePeer
import com.letta.mobile.util.attr
import computer.iroh.Connection
import kotlinx.coroutines.coroutineScope

/**
 * Serves one App Server connection [endpoint] accepted as a link of the Iroh carrier: the carrier's node end splits
 * the connection into its lanes and frames them (same streams, codec, `frame_part` rules and close codes as ever), and
 * the endpoint serves that link exactly as it serves the in-process shell's. Returns when the link has closed. This
 * is what [IrohNodeEndpoint] takes as its `appServerLinks`; its end of the connection is reported with the QUIC
 * connection's own close reason and statistics.
 */
suspend fun serveIrohCarrierLink(connection: Connection, endpoint: IrohNodeEndpoint) {
    serveIrohNodePeer(connection) { link ->
        coroutineScope {
            val wire = NodePeerLinkWire(link, this) {
                recordConnectionClosed("remoteEndpointId" attr link.peer.value, connection)
            }
            endpoint.serveWire(wire)
        }
    }
}
