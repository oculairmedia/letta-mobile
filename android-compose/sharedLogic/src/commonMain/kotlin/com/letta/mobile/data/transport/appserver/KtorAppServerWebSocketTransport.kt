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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

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
 * - Inbound frames are decoded once and delivered by [AppServerInboundDelivery]:
 *   [inboundFrames] carries every frame in socket arrival order (the router reads
 *   this), while [controlFrames] / [streamFrames] are per-type views of the same
 *   sequence for request correlation and stream observers (letta-mobile-by4vm.5).
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
    private val delivery = AppServerInboundDelivery()

    // One generation: its own Job so tearing it down never cancels the caller's scope.
    private val generationJob = Job(scope.coroutineContext.job)
    // The child scope is bound to generationJob and cancelled by coordinator teardown.
    @Suppress("NoDetachedCoroutineLifecycle")
    private val genScope = CoroutineScope(scope.coroutineContext + generationJob)

    private val coordinator = AppServerConnectionGeneration(
        onTeardown = {
            // Fail pending/buffered sends, then cancel the session socket.
            controlCommandQueue.close(CancellationException("App Server connection generation torn down"))
            generationJob.cancel(CancellationException("App Server connection generation torn down"))
        },
    )

    override val inboundFrames: Flow<AppServerReceivedFrame> = delivery.inboundFrames
    override val controlFrames: Flow<AppServerReceivedFrame> = delivery.controlFrames
    override val streamFrames: Flow<AppServerReceivedFrame> = delivery.streamFrames

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
                val closeReason = runBidirectionalSession()
                terminal = closeReason.isTerminal()
                reason = closeReason?.let { "${it.code} ${it.message}".trim() } ?: "App Server session closed"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // letta-mobile-by4vm.117: an engine that cannot run this socket (OkHttp refusing the
            // frame limit) is as final as a rejected token: say so, with the cause, instead of
            // leaving callers to wait out a retry that can never succeed.
            terminal = error.isTerminalHandshakeFailure() || error.isUnsupportedClientConfiguration()
            reason = error.describeChain() ?: "App Server session error"
        } finally {
            coordinator.onSessionClosedOrFailed(terminal = terminal, reason = reason)
        }
    }

    /**
     * @return the peer [CloseReason] observed for this session. Drain timeouts
     * after a clean EOF still return that close reason so a terminal policy
     * close is not misclassified as a retryable delivery failure.
     */
    private suspend fun DefaultClientWebSocketSession.runBidirectionalSession(): CloseReason? = coroutineScope {
        // One ordered delivery path plus per-type views; see AppServerInboundDelivery.
        // A full queue fails the generation explicitly; silently evicting accepted
        // control responses or timeline deltas would corrupt client state.
        val session = delivery.open(this)
        val sender = launch {
            for (command in controlCommandQueue) {
                send(Frame.Text(protocol.encodeCommand(command)))
            }
        }
        var reachedEndOfStream = false
        var drainTimedOut: Boolean
        try {
            receiveFrames(session)
            reachedEndOfStream = true
        } finally {
            sender.cancel()
            session.closeQueues()
            drainTimedOut = !finishDeliveryJobs(reachedEndOfStream, session.jobs)
        }
        val closeReason = closeReason.await()
        if (drainTimedOut && !closeReason.isTerminal()) {
            throw AppServerDeliveryDrainTimeoutException(DELIVERY_DRAIN_TIMEOUT_MILLIS)
        }
        closeReason
    }

    /**
     * @return true when delivery jobs finished within the deadline (or were
     * cancelled because EOF was not reached); false when the drain timed out.
     */
    private suspend fun finishDeliveryJobs(
        reachedEndOfStream: Boolean,
        jobs: List<Job>,
    ): Boolean {
        if (!reachedEndOfStream) {
            jobs.forEach { it.cancel() }
            return true
        }

        val drained = withTimeoutOrNull(DELIVERY_DRAIN_TIMEOUT_MILLIS.milliseconds) {
            jobs.forEach { it.join() }
        } != null
        if (!drained) {
            jobs.forEach { it.cancel() }
            return false
        }
        return true
    }

    private suspend fun DefaultClientWebSocketSession.receiveFrames(session: AppServerInboundDelivery.Session) {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                // protocol.decodeFrame is total: malformed frames surface as
                // AppServerInboundFrame.DecodeFailure rather than throwing, so a
                // bad frame never tears down this receive loop (letta-mobile-lgns8.4).
                session.accept(protocol.decodeFrame(frame.readText()))
            }
        }
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel(CancellationException("App Server transport closed"))
        runCatching { join() }
    }

    private companion object {
        const val DELIVERY_DRAIN_TIMEOUT_MILLIS = 1_000L
    }
}

private class AppServerDeliveryDrainTimeoutException(
    timeoutMillis: Long,
) : IllegalStateException(
    "App Server delivery queues did not drain within ${timeoutMillis}ms after socket EOF",
)

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

/**
 * letta-mobile-by4vm.117: the HTTP client cannot run this socket at all, e.g. Ktor's OkHttp engine
 * rejecting the App Server frame ceiling ("Max frame size switch is not supported in OkHttp
 * engine") or an engine without WebSocket support. Retrying the same client never helps.
 */
internal fun Throwable.isUnsupportedClientConfiguration(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { failure ->
        val text = failure.message.orEmpty()
        text.contains("is not supported in", ignoreCase = true) ||
            text.contains("doesn't support WebSocket", ignoreCase = true) ||
            text.contains("WebSocketCapability", ignoreCase = true)
    }

/** The failure and its causes as one line, so a wrapped engine error keeps its real reason. */
internal fun Throwable.describeChain(): String? =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .distinct()
        .joinToString(": ")
        .takeIf(String::isNotBlank)

private const val MAX_CAUSE_DEPTH = 8

private val TERMINAL_HTTP_STATUS = Regex(
    pattern = "(?i)(?:\\bbut\\s+was\\s+|" +
        "\\b(?:http(?:\\s+response)?(?:\\s+status|\\s+code)?|" +
        "status(?:\\s+code)?|response\\s+code)\\D{0,16})(?:401|403|426)\\b",
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
