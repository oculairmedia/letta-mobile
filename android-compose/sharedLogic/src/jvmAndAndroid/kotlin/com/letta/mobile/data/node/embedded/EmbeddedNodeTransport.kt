package com.letta.mobile.data.node.embedded

import com.letta.mobile.data.node.attach.NodeSessionDialer
import com.letta.mobile.data.node.carrier.NodeCarrier
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** How a shell on the [device] signs in to the node in its own process: by its carrier identity, with no token. */
fun embeddedNodeConnectConfig(device: String): IrohConnectConfig = IrohConnectConfig(
    baseShimUrl = EmbeddedNodeHost.ADDRESS.value,
    token = "",
    deviceId = device,
    clientVersion = "letta-desktop-node",
)

/**
 * The shell's channel transport onto [node] in this process: the same transport, turn engine, admin relays, feed and
 * pushes it uses for a node over Iroh, with each (re)dial a node session opened in [sessionScope].
 */
@Suppress("NoDetachedCoroutineLifecycle") // The transport's job is a child of [sessionScope]'s, so it ends with the window.
fun embeddedNodeTransport(node: NodeCarrier, device: String, sessionScope: CoroutineScope): IrohChannelTransport =
    IrohChannelTransport(
        activeConfigProvider = { embeddedNodeConnectConfig(device) },
        scope = CoroutineScope(sessionScope.coroutineContext + Dispatchers.IO),
        directDialer = NodeSessionDialer(node, EmbeddedNodeHost.ADDRESS, sessionScope),
    )
