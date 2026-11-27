package com.letta.mobile.data.node.attach

import com.letta.mobile.data.controller.extras.relay.AppToolRelay
import com.letta.mobile.data.controller.extras.relay.AppToolRelayResponder
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeInbound
import com.letta.mobile.data.node.carrier.NodeLane
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodeSession
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * The App Server protocol over an authenticated [NodeSession]: the shell's client stack reads and writes the same
 * frames over a node session as over an Iroh connection, so a node in this process and a node elsewhere are reached
 * the same way. The session's control lane is [controlFrames], its push lane is [streamFrames] (turn frames, feed and
 * shared-state pushes), and every `admin_rpc` gets a call lane of its own.
 *
 * The session is already authenticated, so this transport never sends an `auth` frame. It collects the session's
 * inbound frames for the session's whole life, in [scope], and answers the tool calls the node relays to this app with
 * [appToolRelay] when the node negotiated [AppToolRelay.CAPABILITY] (null answers none).
 *
 * The two lanes are delivered independently, as over Iroh, where each has a stream of its own: push frames are handed
 * to a queue of their own and drained into [streamFrames] by a second collector, so a slow push subscriber holds back
 * only later pushes and never a control answer (`runtime_start_response`, `input_accepted`, `sync_response`). The push
 * queue is unbounded, as the session's own inbound buffer is: dropping a turn frame would corrupt the turn, and a
 * bound that suspended would stall the control lane again.
 */
class NodeSessionAppServerTransport(
    private val session: NodeSession,
    private val scope: CoroutineScope,
    private val appToolRelay: AppToolRelayResponder? = AppToolRelayResponder.Default,
) : AppServerTransport {
    private val control = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val pushQueue = Channel<AppServerReceivedFrame>(Channel.UNLIMITED)

    override val controlFrames: SharedFlow<AppServerReceivedFrame> = control.asSharedFlow()
    override val streamFrames: SharedFlow<AppServerReceivedFrame> = stream.asSharedFlow()
    override val isConnected: Flow<Boolean> = session.closure.map { it == null }

    /** [AppServerConnectionState.Ready] while the session's link is open; then why it ended. */
    @Suppress("NoDetachedCoroutineLifecycle") // [scope] is this session's own scope, cancelled when the dial closes.
    val connectionState: StateFlow<AppServerConnectionState> =
        session.closure.map(::stateOf).stateIn(scope, SharingStarted.Eagerly, stateOf(session.closure.value))

    init {
        scope.launch {
            try {
                session.inbound.collect(::route)
            } finally {
                pushQueue.close()
            }
        }
        scope.launch { for (received in pushQueue) stream.emit(received) }
    }

    override suspend fun sendControl(command: AppServerCommand) {
        session.send(NodeFrame.decode(AppServerProtocol.encodeCommand(command)))
    }

    /** One-shot `admin_rpc` [method] on its own call lane, failing after [ADMIN_RPC_TIMEOUT_MS] without an answer. */
    suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
        val answer = withTimeoutOrNull(ADMIN_RPC_TIMEOUT_MS.milliseconds) {
            session.adminRpc(method, appServerAdminRpcParams(path, body))
        } ?: error("admin_rpc timed out for method=$method")
        return AppServerProtocol.decodeFrame(answer.encode(), AppServerChannel.Control).frame
            as? AppServerInboundFrame.AdminRpcResponse
            ?: error("admin_rpc $method answered with an unreadable response")
    }

    /** Streamed `admin_rpc` [method]: every frame on its call lane as JSON, ending after the final response. */
    fun adminRpcStream(method: String, params: JsonObject): Flow<JsonObject> =
        session.adminRpcStream(method, params).map { it.json }

    private suspend fun route(inbound: NodeInbound) {
        val channel = if (inbound.lane == NodeLane.PUSH) AppServerChannel.Stream else AppServerChannel.Control
        val received = AppServerProtocol.decodeFrame(inbound.frame.encode(), channel)
        when {
            // Never suspends: the push collector drains the queue at the push subscribers' pace.
            channel == AppServerChannel.Stream -> pushQueue.trySend(received)
            !answerAppToolCall(received.raw) -> control.emit(received)
        }
    }

    /** Takes a relayed tool call off the control lane and answers it there; false for every other frame. */
    private fun answerAppToolCall(raw: JsonObject): Boolean {
        val relay = appToolRelay ?: return false
        if (!relay.isRequest(raw)) return false
        // A call the node relays without having negotiated the relay is dropped, as over Iroh.
        if (!session.capabilities.supports(AppToolRelay.CAPABILITY)) return true
        scope.launch { relay.answer(raw)?.let { session.send(NodeFrame.decode(it)) } }
        return true
    }

    private fun stateOf(closure: NodeLinkClosure?): AppServerConnectionState = when (closure) {
        null -> AppServerConnectionState.Ready
        else -> AppServerConnectionState.Failed(terminal = closure in TERMINAL_CLOSURES, reason = closure.reason)
    }

    private companion object {
        const val FRAME_BUFFER_CAPACITY = 64

        /** As over Iroh: headroom for a large one-shot read (block.list), bounded so a dead call still fails. */
        const val ADMIN_RPC_TIMEOUT_MS = 30_000L

        /** Closures a redial cannot fix: the node refused this shell. */
        val TERMINAL_CLOSURES = setOf(NodeLinkClosure.AUTH_FAILURES, NodeLinkClosure.PEER_REVOKED)
    }
}
