package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.GiveUpKind
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.controller.reconnect.ReconnectingClientListener
import com.letta.mobile.data.controller.reconnect.ReconnectingClientState
import com.letta.mobile.data.controller.reconnect.toChannelTransportState
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job

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
    val readiness: DesktopAppServerReadinessExpectation,
    /** HTTP `/app-server-info` before every dial; null for the bundled child we just started. */
    val preflight: (suspend () -> AppServerProbeResult)?,
)

/**
 * letta-mobile-by4vm.9: the desktop's ws:// App Server session. The shared
 * [ReconnectingAppServerClient] owns reconnect: it mints a socket generation per dial (each proven
 * by the readiness handshake), runs the shared preflight hook before every dial, and keeps one
 * stable [client] for the gateway's turn engine and router, so a dropped socket no longer needs a
 * gateway rebuild ([DesktopChatController.retryConnection] is the escalation of last resort, once
 * this owner has given up).
 */
internal class DesktopDirectAppServerSession private constructor(
    val client: ReconnectingAppServerClient,
    override val connectionState: StateFlow<ChannelTransportState>,
    private val supervisor: Job,
    private val httpClient: HttpClient,
) : DesktopSelfReconnectingSession {
    override fun resumeNow() = client.resumeNow()

    /** Stops reconnecting, closes the live generation, then the HTTP client. */
    fun resources(): DesktopTransportResources = DesktopTransportResources(
        teardownSteps = listOf(
            { supervisor.cancelAndJoin() },
            { httpClient.close() },
        ),
    )

    companion object {
        /**
         * Dials the first generation and returns once it is Ready. A first dial that fails is
         * reported at once, as before, instead of backing off: the user is looking at it.
         *
         * [scope] is the gateway's own client scope, cancelled when the gateway closes, so the
         * status projection shared on it ends with the session.
         */
        @Suppress("NoDetachedCoroutineLifecycle")
        suspend fun open(
            dial: DesktopDirectDial,
            scope: CoroutineScope,
            listener: ReconnectingClientListener,
            httpClient: HttpClient = createDesktopAppServerHttpClient(),
        ): DesktopDirectAppServerSession {
            val client = ReconnectingAppServerClient(
                connect = { mintGeneration(dial, httpClient, scope) },
                listener = listener,
                telemetryComponent = "DesktopAppServerReconnect",
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
                httpClient.close()
                throw settled.firstDialFailure()
            }
            val status = client.state
                .map { it.toChannelTransportState(serverId = dial.serverUrl) }
                .stateIn(scope, SharingStarted.Eagerly, client.state.value.toChannelTransportState(dial.serverUrl))
            return DesktopDirectAppServerSession(client, status, supervisor, httpClient)
        }

        /** One generation's job is a child of the session scope, closable on its own (as the wrapper's). */
        @Suppress("NoDetachedCoroutineLifecycle")
        private suspend fun mintGeneration(
            dial: DesktopDirectDial,
            httpClient: HttpClient,
            scope: CoroutineScope,
        ): AppServerClientGeneration {
            val endpoint = AppServerEndpoint.fromWebSocketUrl(url = dial.serverUrl, bearerToken = dial.bearerToken)
            val generationJob = Job(scope.coroutineContext.job)
            val generationScope = CoroutineScope(scope.coroutineContext + generationJob)
            val transport = KtorAppServerWebSocketTransport(
                httpClient = httpClient,
                baseUrl = endpoint.address,
                scope = generationScope,
                bearerToken = endpoint.bearerToken,
            )
            val client = DefaultAppServerClient(transport, parentScope = generationScope)
            val close: (String?) -> Unit = { reason ->
                client.failPendingRequests(reason ?: "App Server generation closed")
                generationJob.cancel(CancellationException(reason ?: "App Server generation closed"))
            }
            try {
                awaitDesktopAppServerReadiness(DesktopAppServerReadinessProbe(transport.connectionState, client, dial.readiness))
            } catch (error: Throwable) {
                close(error.message)
                throw error
            }
            return AppServerClientGeneration(client, transport.connectionState, close)
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
