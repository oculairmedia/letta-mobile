@file:OptIn(ExperimentalUuidApi::class)

package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What an App Server must prove on `app_server_info` before a client uses a connection to it. */
data class AppServerReadinessExpectation(
    val expectedBackend: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
) {
    companion object {
        /** A loopback `letta server --backend local`: the desktop bundle, the Android embedded runtime. */
        val LocalBackend = AppServerReadinessExpectation(
            expectedBackend = "local",
            requiredCapabilities = setOf("agent_management", "conversation_management", "memory_management"),
        )
    }
}

/** How one ws:// App Server connection is dialled and proven ready. */
class AppServerWebSocketDial(
    val serverUrl: String,
    val bearerToken: String?,
    val readiness: AppServerReadinessExpectation = AppServerReadinessExpectation(),
    val readinessTimeoutMs: Long = APP_SERVER_READINESS_TIMEOUT_MS,
    /** How long one request may wait for its answer; the on-device runtime's agent_create takes ~40 s. */
    val requestTimeoutMs: Long = APP_SERVER_REQUEST_TIMEOUT_MS,
)

/**
 * One open, ready App Server connection over a WebSocket. [close] fails the pending requests and
 * ends the socket; it never cancels the caller's scope.
 */
class AppServerWebSocketConnection(
    val client: DefaultAppServerClient,
    val connectionState: StateFlow<AppServerConnectionState>,
    /** The stream-delta view of the socket, for observers of turns this client did not start. */
    val streamFrames: Flow<AppServerReceivedFrame>,
    val info: AppServerInfoData,
    val close: (reason: String?) -> Unit,
)

/**
 * letta-mobile-by4vm.110.6: THE "open an App Server over `ws://url` + bearer" composition, shared by
 * desktop (every generation of its self-reconnecting session), web and Android (the embedded
 * runtime's loopback server and a Settings "Remote host" ws:// address). Lifted from the ws:// half
 * of the desktop's direct session.
 *
 * [httpClient] supplies the engine; it must be able to honour `maxFrameSize` (CIO, not OkHttp: see
 * [applyAppServerFrameLimits]). The connection's job is a child of [scope], so it ends with the
 * caller, and returns only once the socket is open and `app_server_info` matched
 * [AppServerWebSocketDial.readiness]. A socket that fails first (a refused port, a rejected token,
 * an engine that cannot be configured) fails the open with that reason at once.
 */
@Suppress("NoDetachedCoroutineLifecycle") // The generation job is a child of [scope] and closed by [AppServerWebSocketConnection.close].
suspend fun openAppServerWebSocket(
    dial: AppServerWebSocketDial,
    httpClient: HttpClient,
    scope: CoroutineScope,
): AppServerWebSocketConnection {
    val endpoint = AppServerEndpoint.fromWebSocketUrl(url = dial.serverUrl, bearerToken = dial.bearerToken)
    val generationJob = Job(scope.coroutineContext.job)
    val generationScope = CoroutineScope(scope.coroutineContext + generationJob)
    val transport = KtorAppServerWebSocketTransport(
        httpClient = httpClient,
        baseUrl = endpoint.address,
        scope = generationScope,
        bearerToken = endpoint.bearerToken,
    )
    val client = DefaultAppServerClient(transport, parentScope = generationScope, requestTimeoutMs = dial.requestTimeoutMs)
    val close: (String?) -> Unit = { reason ->
        client.failPendingRequests(reason ?: "App Server connection closed")
        generationJob.cancel(CancellationException(reason ?: "App Server connection closed"))
    }
    val info = runCatching {
        awaitAppServerReadiness(
            AppServerReadinessProbe(transport.connectionState, client, dial.readiness, dial.readinessTimeoutMs),
        )
    }.onFailure { error -> close(error.message) }.getOrThrow()
    return AppServerWebSocketConnection(client, transport.connectionState, transport.streamFrames, info, close)
}

/** One connection's readiness check: the socket state to watch and the client to ask. */
class AppServerReadinessProbe(
    val connectionState: StateFlow<AppServerConnectionState>,
    val client: AppServerClient,
    val expectation: AppServerReadinessExpectation,
    val timeoutMs: Long = APP_SERVER_READINESS_TIMEOUT_MS,
    val requestIdFactory: () -> String = { "app-server-info-${Uuid.random()}" },
)

/**
 * Makes one App Server connection prove protocol compatibility before a client publishes anything
 * backed by it: the socket opens (or its failure is reported at once, with its reason), then
 * `app_server_info` must match [AppServerReadinessProbe.expectation].
 */
suspend fun awaitAppServerReadiness(probe: AppServerReadinessProbe): AppServerInfoData =
    withTimeoutOrNull(probe.timeoutMs.milliseconds) {
        when (val settled = probe.connectionState.first { it.isReady || it is AppServerConnectionState.Failed }) {
            AppServerConnectionState.Ready -> Unit
            is AppServerConnectionState.Failed -> error(
                "App Server connection failed before readiness: " +
                    settled.reason.orEmpty().ifBlank { "unknown transport failure" },
            )
            else -> error("App Server reached an invalid readiness state: $settled")
        }
        val response = probe.client.appServerInfo(AppServerCommand.AppServerInfo(probe.requestIdFactory()))
        if (!response.success) {
            error("App Server handshake failed: ${response.error ?: "unknown protocol failure"}")
        }
        val info = response.info
            ?.requireCompatibleWith(probe.expectation.toCompatibilityRequirement())
            ?: error("App Server handshake returned no server information")
        check(probe.connectionState.value.isReady) { "App Server connection was lost during protocol handshake" }
        info
    } ?: error("App Server readiness timed out after ${probe.timeoutMs}ms")

private fun AppServerReadinessExpectation.toCompatibilityRequirement() = AppServerCompatibilityRequirement(
    protocolVersion = SUPPORTED_APP_SERVER_PROTOCOL_VERSION,
    expectedBackend = expectedBackend,
    requiredCapabilities = requiredCapabilities + "runtime_start",
    requiredDisabledCapabilities = setOf("split_channels"),
)

private const val SUPPORTED_APP_SERVER_PROTOCOL_VERSION = 1
const val APP_SERVER_READINESS_TIMEOUT_MS: Long = 15_000L
const val APP_SERVER_REQUEST_TIMEOUT_MS: Long = 30_000L
