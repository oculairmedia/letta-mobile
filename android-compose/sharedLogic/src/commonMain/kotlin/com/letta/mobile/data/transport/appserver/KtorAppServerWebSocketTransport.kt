package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Ktor-backed App Server transport (letta-mobile-lgns8.21.1: one bidirectional
 * WebSocket session with truthful readiness).
 *
 * Letta Code ≥ 0.29.7 rejects legacy `?channel=control|stream` upgrades with
 * HTTP 426. This transport opens a single `/ws` session:
 * - [connectionState] starts [AppServerConnectionState.Disconnected] — never
 *   optimistically connected — and reaches [AppServerConnectionState.Ready]
 *   once the socket is open.
 * - Close or failure tears down the generation (closes the command queue so
 *   pending sends fail) and moves to [AppServerConnectionState.Failed],
 *   distinguishing terminal auth/config failures from retryable drops.
 * - Inbound frames are decoded once, then demuxed into [controlFrames] /
 *   [streamFrames] by message type so existing request correlation and stream
 *   observers keep working without dual sockets.
 *
 * `bearerToken` is intentionally optional: loopback App Server runs omit WS auth,
 * while non-loopback/headless hosts should launch `letta app-server` / `letta
 * server --listen` with `--ws-auth` and pass the matching bearer token here.
 */
class KtorAppServerWebSocketTransport(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    scope: CoroutineScope,
    private val bearerToken: String? = null,
    private val protocol: AppServerProtocol = AppServerProtocol,
) : AppServerTransport {
    private val controlCommandQueue = Channel<AppServerCommand>(Channel.BUFFERED)
    private val controlFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val streamFrameFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)

    // One generation: its own Job so tearing it down never cancels the caller's scope.
    private val generationJob = Job(scope.coroutineContext.job)
    private val genScope = CoroutineScope(scope.coroutineContext + generationJob)

    private val coordinator = AppServerConnectionGeneration(
        onTeardown = {
            // Fail pending/buffered sends, then cancel the session socket.
            controlCommandQueue.close(CancellationException("App Server connection generation torn down"))
            generationJob.cancel(CancellationException("App Server connection generation torn down"))
        },
    )

    override val controlFrames: Flow<AppServerReceivedFrame> = controlFrameFlow.asSharedFlow()
    override val streamFrames: Flow<AppServerReceivedFrame> = streamFrameFlow.asSharedFlow()

    /** Explicit lifecycle of the connection generation. */
    val connectionState: StateFlow<AppServerConnectionState> = coordinator.state
    override val isConnected: Flow<Boolean> = coordinator.state.map { it.isReady }

    init {
        genScope.launch {
            coordinator.markConnecting()
            runSession()
        }
    }

    override suspend fun sendControl(command: AppServerCommand) {
        controlCommandQueue.send(command)
    }

    suspend fun close() {
        controlCommandQueue.close()
        generationJob.cancelAndJoinQuietly()
    }

    private suspend fun runSession() {
        var terminal = false
        var reason: String? = null
        try {
            httpClient.webSocket(
                urlString = appServerUrl(baseUrl),
                request = { bearerToken?.let(::bearerAuth) },
            ) {
                coordinator.onSessionOpen()
                runBidirectionalSession()
                val closeReason = closeReason.await()
                terminal = closeReason.isTerminal()
                reason = closeReason?.let { "${it.code} ${it.message}".trim() } ?: "App Server session closed"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            terminal = error.isTerminalHandshakeFailure()
            reason = error.message ?: "App Server session error"
        } finally {
            coordinator.onSessionClosedOrFailed(terminal = terminal, reason = reason)
        }
    }

    private suspend fun DefaultClientWebSocketSession.runBidirectionalSession() = coroutineScope {
        // Both queues are deliberately lossless. Control responses are
        // correctness-critical, and stream deltas form the durable timeline
        // projection, so neither may be silently evicted. RuntimeEventFanout owns
        // bounded per-subscriber buffering after this socket-level handoff.
        val controlDeliveryQueue = Channel<AppServerReceivedFrame>(Channel.UNLIMITED)
        val streamDeliveryQueue = Channel<AppServerReceivedFrame>(Channel.UNLIMITED)
        val sender = launch {
            for (command in controlCommandQueue) {
                send(Frame.Text(protocol.encodeCommand(command)))
            }
        }
        val controlDelivery = launch {
            for (frame in controlDeliveryQueue) {
                controlFrameFlow.emit(frame)
            }
        }
        val streamDelivery = launch {
            for (frame in streamDeliveryQueue) {
                streamFrameFlow.emit(frame)
            }
        }
        try {
            receiveAndDemuxFrames(controlDeliveryQueue, streamDeliveryQueue)
        } finally {
            sender.cancel()
            controlDeliveryQueue.close()
            streamDeliveryQueue.close()
            controlDelivery.cancel()
            streamDelivery.cancel()
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveAndDemuxFrames(
        controlDeliveryQueue: Channel<AppServerReceivedFrame>,
        streamDeliveryQueue: Channel<AppServerReceivedFrame>,
    ) {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                // protocol.decodeFrame is total: malformed frames surface as
                // AppServerInboundFrame.DecodeFailure rather than throwing, so a
                // bad frame never tears down this receive loop (letta-mobile-lgns8.4).
                val received = protocol.decodeFrame(frame.readText())
                when (received.channel) {
                    AppServerChannel.Control -> check(controlDeliveryQueue.trySend(received).isSuccess) {
                        "control delivery queue closed while WebSocket receive loop is active"
                    }
                    AppServerChannel.Stream -> check(streamDeliveryQueue.trySend(received).isSuccess) {
                        "stream delivery queue closed while WebSocket receive loop is active"
                    }
                }
            }
        }
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel(CancellationException("App Server transport closed"))
        runCatching { join() }
    }

    private companion object {
        const val FRAME_BUFFER_CAPACITY = 64
    }
}

/**
 * A close is terminal (must not be blindly retried) when the peer signals an
 * auth/policy/consistency violation rather than a normal or transient close.
 */
internal fun CloseReason?.isTerminal(): Boolean {
    val known = this?.knownReason ?: return false
    return known == CloseReason.Codes.VIOLATED_POLICY ||
        known == CloseReason.Codes.CANNOT_ACCEPT ||
        known == CloseReason.Codes.NOT_CONSISTENT
}

/**
 * A handshake failure is terminal when it reflects auth/authorization rejection
 * (HTTP 401/403) or the legacy split-channel rejection (HTTP 426) rather than a
 * transient connect error.
 */
internal fun Throwable.isTerminalHandshakeFailure(): Boolean {
    val text = (message ?: "") + " " + (cause?.message ?: "")
    return TERMINAL_HTTP_STATUS.containsMatchIn(text) ||
        text.contains("Unauthorized", ignoreCase = true) ||
        text.contains("Forbidden", ignoreCase = true) ||
        text.contains("Upgrade Required", ignoreCase = true)
}

private val TERMINAL_HTTP_STATUS = Regex(
    pattern = "(?i)\\b(?:http(?:\\s+response)?(?:\\s+status|\\s+code)?|" +
        "status(?:\\s+code)?|response\\s+code)\\D{0,16}(?:401|403|426)\\b",
)

/**
 * Resolve the single bidirectional App Server WebSocket URL.
 *
 * Strips any legacy `channel` query parameter — Letta Code ≥ 0.29.7 rejects
 * `?channel=control|stream` with HTTP 426.
 */
internal fun appServerUrl(baseUrl: String): String =
    URLBuilder().takeFrom(baseUrl).apply {
        encodedPath = "/ws"
        parameters.clear()
    }.buildString()

/** @deprecated Prefer [appServerUrl]; both historical channels resolve to the same socket. */
@Deprecated(
    message = "App Server uses one bidirectional WebSocket; channel URLs are rejected by ≥0.29.7",
    replaceWith = ReplaceWith("appServerUrl(baseUrl)"),
)
internal fun appServerChannelUrl(baseUrl: String, channel: AppServerChannel): String {
    // Keep the parameter referenced so call sites that still pass a channel compile
    // during the migration window without unused-parameter warnings.
    @Suppress("UNUSED_VARIABLE")
    val ignored = channel
    return appServerUrl(baseUrl)
}
