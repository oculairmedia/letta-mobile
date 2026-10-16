package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.GiveUpKind
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.controller.reconnect.ReconnectingClientListener
import com.letta.mobile.data.controller.reconnect.ReconnectingClientState
import com.letta.mobile.data.controller.reconnect.toChannelTransportState
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.data.transport.appserver.AppServerReadinessExpectation
import com.letta.mobile.data.transport.appserver.AppServerWebSocketDial
import com.letta.mobile.data.transport.appserver.openAppServerWebSocket
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the gateway exposes of a session that reconnects itself (letta-mobile-by4vm.9). */
internal interface DesktopSelfReconnectingSession {
    /** The shared session-status model the chat controller's connection watcher reads. */
    val connectionState: StateFlow<ChannelTransportState>

    /** The machine woke: cut the current backoff short (F04). */
    fun resumeNow()
}

/**
 * A gateway whose session may reconnect itself (letta-mobile-by4vm.9). While [ownsReconnect], a
 * drop the session is redialling is not the chat controller's to escalate, and a wake from sleep
 * goes to [resumeSession] instead of a gateway rebuild.
 */
internal interface DesktopReconnectOwningGateway {
    val ownsReconnect: Boolean

    /** Returns true when the gateway's own reconnect owner took the wake. */
    fun resumeSession(): Boolean
}

/** How one direct-socket generation is dialled and proven ready. */
internal class DesktopDirectDial(
    val serverUrl: String,
    val bearerToken: String?,
    val readiness: AppServerReadinessExpectation,
    /** HTTP `/app-server-info` before every dial; null for the bundled child we just started. */
    val preflight: (suspend () -> AppServerProbeResult)?,
)

/** How a session mints its generations, and what it releases once it stops (letta-mobile-by4vm.56). */
internal class SessionDial(
    val mint: suspend () -> AppServerClientGeneration,
    /** Runs before every dial; null when the address cannot be probed or was just proven. */
    val preflight: (suspend () -> AppServerProbeResult)?,
    val telemetryComponent: String,
    /** Resources shared by every generation, released after the supervisor stops. */
    val releaseSteps: List<suspend () -> Unit> = emptyList(),
)

/**
 * letta-mobile-by4vm.9: the desktop's self-reconnecting App Server session. The shared
 * [ReconnectingAppServerClient] owns reconnect: it mints a generation per dial (each proven ready
 * before it is handed over), runs the preflight hook before every dial, and keeps one stable
 * [client] for the gateway's turn engine and router, so a dropped connection no longer needs a
 * gateway rebuild ([DesktopChatController.retryConnection] is the escalation of last resort, once
 * this owner has given up). ws:// generations are WebSocket dials ([open]); iroh:// generations are
 * QUIC dials plus the auth handshake ([openWith] over a [DesktopIrohGenerationSource],
 * letta-mobile-by4vm.56).
 */
internal class DesktopDirectAppServerSession private constructor(
    val client: ReconnectingAppServerClient,
    override val connectionState: StateFlow<ChannelTransportState>,
    private val supervisor: Job,
    private val releaseSteps: List<suspend () -> Unit>,
) : DesktopSelfReconnectingSession {
    override fun resumeNow() = client.resumeNow()

    /** Stops reconnecting, closes the live generation, then whatever the dial shares across generations. */
    fun resources(): DesktopTransportResources = DesktopTransportResources(
        teardownSteps = listOf<suspend () -> Unit>({ supervisor.cancelAndJoin() }) + releaseSteps,
    )

    companion object {
        /** ws:// — one WebSocket per generation over a shared HTTP client. */
        suspend fun open(
            dial: DesktopDirectDial,
            scope: CoroutineScope,
            listener: ReconnectingClientListener,
            httpClient: HttpClient = createDesktopAppServerHttpClient(),
        ): DesktopDirectAppServerSession = openWith(
            serverId = dial.serverUrl,
            scope = scope,
            listener = listener,
            dial = SessionDial(
                // letta-mobile-by4vm.110.6: the ws:// open is the one Android and web share.
                mint = {
                    openAppServerWebSocket(
                        AppServerWebSocketDial(dial.serverUrl, dial.bearerToken, dial.readiness),
                        httpClient,
                        scope,
                    ).let { AppServerClientGeneration(it.client, it.connectionState, it.close) }
                },
                preflight = dial.preflight,
                telemetryComponent = "DesktopAppServerReconnect",
                releaseSteps = listOf { httpClient.close() },
            ),
        )

        /**
         * Dials the first generation and returns once it is Ready. A first dial that fails is
         * reported at once, as before, instead of backing off: the user is looking at it.
         *
         * [scope] is the gateway's own client scope, cancelled when the gateway closes, so the
         * status projection shared on it ends with the session.
         */
        @Suppress("NoDetachedCoroutineLifecycle")
        suspend fun openWith(
            serverId: String,
            scope: CoroutineScope,
            listener: ReconnectingClientListener,
            dial: SessionDial,
        ): DesktopDirectAppServerSession {
            val client = ReconnectingAppServerClient(
                connect = dial.mint,
                listener = listener,
                telemetryComponent = dial.telemetryComponent,
                preflight = dial.preflight,
                // Turns survive a reconnect here, so they are not fenced by generation.
                stampGenerations = false,
            )
            val supervisor = client.start(scope)
            val settled = client.state.first {
                it is ReconnectingClientState.Ready || it is ReconnectingClientState.GaveUp ||
                    it is ReconnectingClientState.BackingOff
            }
            if (settled !is ReconnectingClientState.Ready) {
                supervisor.cancelAndJoin()
                dial.releaseSteps.forEach { release -> runCatching { release() } }
                throw settled.firstDialFailure()
            }
            val status = client.state
                .map { it.toChannelTransportState(serverId = serverId) }
                .stateIn(scope, SharingStarted.Eagerly, client.state.value.toChannelTransportState(serverId))
            return DesktopDirectAppServerSession(client, status, supervisor, dial.releaseSteps)
        }
    }
}

/** The exception a failed first dial surfaces with; probe verdicts keep their summary() prefix. */
private fun ReconnectingClientState.firstDialFailure(): Exception = when (this) {
    is ReconnectingClientState.GaveUp -> when (kind) {
        GiveUpKind.Authentication -> DesktopAppServerUnusableException(AppServerProbeResult.Authentication(reason.orEmpty()))
        GiveUpKind.Incompatible -> DesktopAppServerUnusableException(AppServerProbeResult.Incompatible(reason.orEmpty()))
        GiveUpKind.Rejected, GiveUpKind.Exhausted -> IllegalStateException(reason ?: "App Server connection failed")
    }
    is ReconnectingClientState.BackingOff -> IllegalStateException(reason ?: "App Server connection failed")
    else -> IllegalStateException("App Server connection did not become ready: $this")
}
