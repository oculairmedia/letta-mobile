package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs one method the other side calls; a notification's answer is ignored. Throw [LcpCallException] to answer an error. */
fun interface LcpHandler {
    suspend fun handle(params: JsonObject): JsonElement
}

/** How much a peer takes on at once (backpressure) and the deadline of a method without one. */
data class LcpPeerLimits(
    val maxConcurrentInbound: Int = LcpWire.MAX_CONCURRENT_INBOUND,
    val maxOutstandingOutbound: Int = LcpWire.MAX_OUTSTANDING_OUTBOUND,
    val defaultDeadline: Duration = 30.seconds,
)

data class LcpPeerConfig(val side: LcpSide, val guard: LcpGuard = LcpGuards.NONE, val limits: LcpPeerLimits = LcpPeerLimits())

/**
 * One end of an LCP wire v1 connection, transport-agnostic: it mints ids and correlates answers,
 * sends and receives notifications, runs the other side's requests on its registered handlers
 * under each method's deadline, honours `$/cancel` both ways, and holds every call to the
 * [LcpPeerConfig.guard] (session state, capabilities, secrets).
 *
 * Backpressure: a caller beyond [LcpPeerLimits.maxOutstandingOutbound] waits for a slot, the
 * transport's send waits for its reader, and requests beyond [LcpPeerLimits.maxConcurrentInbound]
 * are answered [LcpErrorCode.OVERLOADED] instead of stalling the reader (which must keep reading
 * the answers the running handlers wait on). Notifications run in arrival order, one at a time; a
 * notification handler must not wait on the other side.
 */
class LcpPeer(private val transport: LcpTransport, private val config: LcpPeerConfig, private val scope: CoroutineScope) {
    private val calls = LcpCallTable()
    private val io = LcpIo(transport)
    private val slots = Semaphore(config.limits.maxOutstandingOutbound)
    private val inbound = LcpInbound(config, calls, io, scope)
    private var reader: Job? = null

    val side: LcpSide get() = config.side

    /** Calls in flight both ways; zero when the peer is idle. */
    val inFlight: StateFlow<Int> get() = calls.inFlight

    /** Serves [method] from the other side with [handler]; register before [start]. */
    fun handle(method: LcpMethod, handler: LcpHandler) = inbound.register(method, handler)

    /** Starts reading; the returned job ends when the other side goes away or [close] is called. */
    fun start(): Job = scope.launch {
        try {
            inbound.run()
        } finally {
            withContext(NonCancellable) { shutdown() }
        }
    }.also { reader = it }

    /**
     * Calls [method] on the other side and waits for its result, at most [timeout] (the method's
     * deadline by default). On timeout or cancellation the other side is sent `$/cancel`. Throws
     * [LcpCallException] for an error answer or a local refusal.
     */
    suspend fun request(method: LcpMethod, params: JsonObject, timeout: Duration? = null): JsonElement {
        require(method.isRequest && method.direction.deliversTo(config.side.other())) { "${method.wire} is not a request this side sends" }
        val admitted = admitOutgoing(method, params)
        return slots.withPermit { exchange(method, admitted, timeout ?: deadlineOf(method)) }
    }

    /** Sends the notification [method]. */
    suspend fun notify(method: LcpMethod, params: JsonObject) {
        require(!method.isRequest && method.direction.deliversTo(config.side.other())) { "${method.wire} is not a notification this side sends" }
        io.send(JsonRpcMessage.Notification(method.wire, admitOutgoing(method, params)))
    }

    /** Waits until no call is in flight either way. */
    suspend fun awaitIdle() {
        calls.inFlight.first { it == 0 }
    }

    /** Stops reading, fails every waiting call with [LcpErrorCode.CLOSED] and closes the transport. */
    fun close() {
        reader?.cancel()
        shutdown()
    }

    private fun shutdown() {
        calls.failAll(JsonRpcError(LcpErrorCode.CLOSED, "the connection closed"))
        transport.close()
    }

    private fun admitOutgoing(method: LcpMethod, params: JsonObject): JsonObject = when (val admission = config.guard.outgoing(method, params)) {
        is LcpAdmission.Admit -> admission.params
        is LcpAdmission.Refuse -> throw LcpCallException(admission.error)
    }

    private suspend fun exchange(method: LcpMethod, params: JsonObject, timeout: Duration): JsonElement {
        val (id, answer) = calls.open()
        try {
            io.send(JsonRpcMessage.Request(id, method.wire, params))
            val reply = withTimeoutOrNull(timeout) { answer.await() } ?: throw deadlinePassed(method, id, timeout)
            return settle(method, reply)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cancelRemote(id) }
            throw e
        } finally {
            calls.forget(id)
        }
    }

    private suspend fun deadlinePassed(method: LcpMethod, id: JsonPrimitive, timeout: Duration): LcpCallException {
        cancelRemote(id)
        config.guard.completed(method, ok = false)
        return LcpCallException(LcpErrorCode.DEADLINE_EXCEEDED, "${method.wire} took longer than $timeout")
    }

    private suspend fun cancelRemote(id: JsonPrimitive) {
        io.trySend(JsonRpcMessage.Notification(LcpMethod.CANCEL.wire, LcpCalls.CANCEL.encodeParams(CancelParams(id))))
    }

    private fun settle(method: LcpMethod, reply: JsonRpcMessage): JsonElement {
        val ok = reply is JsonRpcMessage.Success
        try {
            if (reply !is JsonRpcMessage.Success) throw LcpCallException(config.guard.error(failureOf(reply)))
            return config.guard.result(method, reply.result)
        } finally {
            config.guard.completed(method, ok)
        }
    }

    private fun failureOf(reply: JsonRpcMessage): JsonRpcError =
        (reply as? JsonRpcMessage.Failure)?.error ?: JsonRpcError(LcpErrorCode.INTERNAL_ERROR, "not an answer")

    private fun deadlineOf(method: LcpMethod): Duration = method.deadline ?: config.limits.defaultDeadline
}

/** A transport seen as messages: encodes and sends them, holding each to the size cap, and reads frames. */
internal class LcpIo(private val transport: LcpTransport) {
    suspend fun receive(): LcpInboundFrame? = transport.receive()

    suspend fun send(message: JsonRpcMessage) {
        val text = JsonRpcCodec.encode(message)
        if (JsonRpcCodec.utf8Size(text) > LcpWire.MAX_MESSAGE_BYTES) {
            throw LcpCallException(LcpErrorCode.MESSAGE_TOO_LARGE, "the message exceeds ${LcpWire.MAX_MESSAGE_BYTES} bytes")
        }
        transport.send(text)
    }

    /** Sends [message], ignoring a closed transport; an oversized answer becomes an error answer. */
    suspend fun trySend(message: JsonRpcMessage) {
        try {
            send(message)
        } catch (e: LcpCallException) {
            if (e.code == LcpErrorCode.MESSAGE_TOO_LARGE && message is JsonRpcMessage.Success) trySend(JsonRpcMessage.Failure(message.id, e.error))
        }
    }
}
