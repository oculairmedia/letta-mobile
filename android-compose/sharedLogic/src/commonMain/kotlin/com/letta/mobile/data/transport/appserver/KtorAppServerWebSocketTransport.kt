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
import kotlinx.coroutines.cancel
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
 * Ktor-backed App Server transport (letta-mobile-lgns8.2: atomic dual-WebSocket
 * connection generation with truthful readiness).
 *
 * The control and stream sockets form ONE connection generation:
 * - [connectionState] starts [AppServerConnectionState.Disconnected] — never
 *   optimistically connected — and only reaches [AppServerConnectionState.Ready]
 *   once BOTH sockets are open.
 * - The first socket to close or fail tears down the whole generation (cancels
 *   the sibling socket and closes the control queue so pending sends fail) and
 *   moves to [AppServerConnectionState.Failed], distinguishing terminal
 *   auth/config failures from retryable drops.
 *
 * `bearerToken` is intentionally optional: loopback App Server runs omit WS auth,
 * while non-loopback/headless hosts should launch `letta app-server` with
 * `--ws-auth` and pass the matching bearer token here.
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
            // Fail pending/buffered sends, then cancel both sockets atomically.
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
            launch { runChannel(AppServerChannel.Control) }
            launch { runChannel(AppServerChannel.Stream) }
        }
    }

    override suspend fun sendControl(command: AppServerCommand) {
        controlCommandQueue.send(command)
    }

    suspend fun close() {
        controlCommandQueue.close()
        generationJob.cancelAndJoinQuietly()
    }

    private suspend fun runChannel(channel: AppServerChannel) {
        var terminal = false
        var reason: String? = null
        try {
            httpClient.webSocket(
                urlString = appServerChannelUrl(baseUrl, channel),
                request = { bearerToken?.let(::bearerAuth) },
            ) {
                coordinator.onChannelOpen(channel)
                if (channel == AppServerChannel.Control) {
                    runControlSession()
                } else {
                    receiveFrames(channel, streamFrameFlow)
                }
                val closeReason = closeReason.await()
                terminal = closeReason.isTerminal()
                reason = closeReason?.let { "${it.code} ${it.message}".trim() } ?: "${channel.name} channel closed"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            terminal = error.isTerminalHandshakeFailure()
            reason = error.message ?: "${channel.name} channel error"
        } finally {
            // Idempotent: the first channel to finish finalizes the generation and
            // triggers teardown; the sibling's later call is a no-op.
            coordinator.onChannelClosedOrFailed(terminal = terminal, reason = reason)
        }
    }

    private suspend fun DefaultClientWebSocketSession.runControlSession() = coroutineScope {
        val sender = launch {
            for (command in controlCommandQueue) {
                send(Frame.Text(protocol.encodeCommand(command)))
            }
        }
        try {
            receiveFrames(AppServerChannel.Control, controlFrameFlow)
        } finally {
            sender.cancel()
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveFrames(
        channel: AppServerChannel,
        sink: MutableSharedFlow<AppServerReceivedFrame>,
    ) {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                // protocol.decodeFrame is total: malformed frames surface as
                // AppServerInboundFrame.DecodeFailure rather than throwing, so a
                // bad frame never tears down this receive loop (letta-mobile-lgns8.4).
                sink.emit(protocol.decodeFrame(frame.readText(), channel))
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
 * (HTTP 401/403) rather than a transient connect error.
 */
internal fun Throwable.isTerminalHandshakeFailure(): Boolean {
    val text = (message ?: "") + " " + (cause?.message ?: "")
    return text.contains("401") ||
        text.contains("403") ||
        text.contains("Unauthorized", ignoreCase = true) ||
        text.contains("Forbidden", ignoreCase = true)
}

internal fun appServerChannelUrl(baseUrl: String, channel: AppServerChannel): String {
    val channelName = when (channel) {
        AppServerChannel.Control -> "control"
        AppServerChannel.Stream -> "stream"
    }
    return URLBuilder().takeFrom(baseUrl).apply {
        encodedPath = "/ws"
        parameters.clear()
        parameters.append("channel", channelName)
    }.buildString()
}
