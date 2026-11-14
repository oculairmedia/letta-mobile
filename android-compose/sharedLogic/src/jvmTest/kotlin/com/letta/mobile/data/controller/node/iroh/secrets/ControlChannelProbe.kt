package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohConnectionAuthentication
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import computer.iroh.Connection
import computer.iroh.NoHandle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex

/**
 * A node connection serving [routes]' router, built like the endpoint builds one for a peer at
 * [HUMAN_ENDPOINT] under [policy] with [pairing] but over no real QUIC connection: enough to hand frames to its
 * control-channel handler and read the request context it builds. Its controller refuses every
 * call, so a frame that reaches a turn fails loudly.
 */
internal fun controlConnection(
    routes: SecretRouteHarness,
    policy: IrohAuthPolicy = IrohAuthPolicy.PairedPeersOnly,
    pairing: IrohPairingService = IrohPairingService(InMemoryPairedPeerStore()),
): IrohNodeConnection = IrohNodeConnection(
    connection = Connection(NoHandle),
    controller = refusingController(),
    adminRpcRouter = routes.router,
    authentication = IrohConnectionAuthentication(
        authPolicy = policy,
        pairingService = pairing,
        remoteEndpointId = HUMAN_ENDPOINT,
    ),
)

/** The admin RPC request context this connection builds for its peer's calls (its private builder). */
internal fun IrohNodeConnection.requestContext(): AdminRpcRequestContext {
    val builder = IrohNodeConnection::class.java.getDeclaredMethod("currentAdminRpcRequestContext")
    builder.isAccessible = true
    return builder.invoke(this) as AdminRpcRequestContext
}

/**
 * The control channel's answer to [frameJson], from the connection's private frame handler (the
 * one its control loop calls for every frame it reads). Frames that parse never reach the stream
 * the handler is given, which is absent here.
 */
internal suspend fun IrohNodeConnection.controlFrame(frameJson: String): String? = coroutineScope {
    val handler = IrohNodeConnection::class.java.declaredMethods.single { it.name == "handleControlFrame" }
    handler.isAccessible = true
    val connection = this@controlFrame
    val scope = this
    suspendCoroutineUninterceptedOrReturn<String?> { continuation ->
        try {
            handler.invoke(connection, scope, frameJson, null, LinkedHashSet<Job>(), Mutex(), continuation)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}

private fun refusingController(): AppServerController = AppServerController::class.java.cast(
    Proxy.newProxyInstance(AppServerController::class.java.classLoader, arrayOf(AppServerController::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "RefusingController"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("unexpected controller call: ${method.name}")
        }
    },
)
