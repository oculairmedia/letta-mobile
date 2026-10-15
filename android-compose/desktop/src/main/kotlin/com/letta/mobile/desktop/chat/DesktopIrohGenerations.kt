package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job

/**
 * letta-mobile-by4vm.56: where an iroh:// session's generations come from. The desktop gateway hands
 * [mint] to the shared reconnect owner, so a lost QUIC connection is redialled behind one stable
 * client instead of rebuilding the gateway.
 */
interface DesktopIrohGenerationSource {
    /** What the host advertised in the latest successful auth (letta-mobile-by4vm.50); empty before. */
    val hostCapabilities: Set<String>

    /** Dials and authenticates one generation; throws when the dial fails before it is usable. */
    suspend fun mint(): AppServerClientGeneration
}

/**
 * Mints iroh:// generations: binds a local endpoint with the desktop's stable identity, dials the
 * backend over QUIC, waits for the connection and runs the auth/capability handshake. The
 * generation is Ready once authenticated and Failed when the QUIC connection is lost. A refused
 * auth is a terminal failure, so a revoked device is reported once instead of redialled. A failure
 * before the hand-off tears the endpoint and transport down before rethrowing.
 */
internal class DesktopIrohGenerationMinter(
    private val serverUrl: String,
    private val accessToken: String?,
    private val identity: () -> ByteArray,
    /** The session's client scope: each generation's transport runs in a child job of it. */
    private val scope: CoroutineScope,
) : DesktopIrohGenerationSource {
    @Volatile
    override var hostCapabilities: Set<String> = emptySet()
        private set

    @Suppress("NoDetachedCoroutineLifecycle") // Owned: the generation's close cancels its job.
    override suspend fun mint(): AppServerClientGeneration {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = identity()))
        val generationJob = Job(scope.coroutineContext.job)
        val state = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Connecting)
        var resources: DesktopTransportResources? = null
        val close: (String?) -> Unit = { reason ->
            resources?.close() ?: closeEndpoint(endpoint)
            generationJob.cancel(CancellationException(reason ?: "Iroh generation closed"))
        }
        try {
            val transport = IrohAppServerTransportAdapter(
                endpoint = endpoint,
                onConnectionLost = { reason -> state.value = AppServerConnectionState.Failed(terminal = false, reason = reason) },
            ).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = IrohChannelTransport.normalizeIrohAddress(serverUrl)),
                scope = CoroutineScope(scope.coroutineContext + generationJob),
            ) as IrohAppServerTransport
            resources = DesktopTransportResources(endpoint, transport)
            transport.awaitConnectionReady()
            val client = DefaultAppServerClient(transport, parentScope = CoroutineScope(scope.coroutineContext + generationJob))
            authenticate(client, state)
            hostCapabilities = transport.serverCapabilities
            return AppServerClientGeneration(client, state, close)
        } catch (error: Throwable) {
            close(error.message)
            throw error
        }
    }

    /** Ready on success; a refusal fails the generation terminally instead of throwing. */
    private suspend fun authenticate(client: DefaultAppServerClient, state: MutableStateFlow<AppServerConnectionState>) {
        val next = try {
            authenticateDesktopIrohAppServer(client = client, accessToken = accessToken)
            AppServerConnectionState.Ready
        } catch (refused: DesktopIrohAuthRefusedException) {
            AppServerConnectionState.Failed(terminal = true, reason = refused.message)
        }
        // A connection lost during the handshake stays Failed.
        state.compareAndSet(AppServerConnectionState.Connecting, next)
    }

    private fun closeEndpoint(endpoint: Endpoint) {
        DesktopTransportResources(teardownSteps = listOf({ endpoint.shutdown() }, { endpoint.close() })).close()
    }
}
