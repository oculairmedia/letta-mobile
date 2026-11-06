package com.letta.mobile.wrapper.notebook

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohConnectionAuthentication
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import computer.iroh.Connection
import computer.iroh.NoHandle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A paired peer's authenticated connection to [router], as the host sees it. */
internal fun notebookPeer(router: AdminRpcRouter, peer: String): IrohNodeConnection {
    val peers = InMemoryPairedPeerStore()
    peers.save(PairedPeer(nodeId = peer, name = peer, pairedAtMs = 0L, capabilities = emptySet()))
    val controller = AppServerController::class.java.cast(Proxy.newProxyInstance(
        AppServerController::class.java.classLoader, arrayOf(AppServerController::class.java),
    ) { _, method, _ -> error("Unexpected controller call ${method.name}") })
    return IrohNodeConnection(
        connection = Connection(NoHandle), controller = controller, adminRpcRouter = router,
        authentication = IrohConnectionAuthentication(
            authPolicy = IrohAuthPolicy.PairedPeersOnly,
            pairingService = IrohPairingService(peers), remoteEndpointId = peer,
        ),
    )
}

/** The full admin_rpc response frame for one call over this connection. */
internal suspend fun IrohNodeConnection.adminFrame(method: String, params: JsonObject): JsonObject {
    val frame = buildJsonObject { put("request_id", "test"); put("method", method); put("params", params) }
    val raw = suspendCoroutine<String> { continuation ->
        val handler = IrohNodeConnection::class.java.getDeclaredMethod(
            "handleControlAdminRpc", JsonObject::class.java, Continuation::class.java,
        ).apply { isAccessible = true }
        try {
            val result = handler.invoke(this, frame, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result as String)
        } catch (error: InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        }
    }
    return Json.parseToJsonElement(raw).jsonObject
}

/** The client's invoker over this connection: a failed call throws, as over a real transport. */
internal fun IrohNodeConnection.asInvoker(): AdminRpcInvoker = AdminRpcInvoker { method, params ->
    val response = adminFrame(method, params)
    check(response["success"]?.jsonPrimitive?.content == "true") { "$method failed: $response" }
    response["result"]
}
