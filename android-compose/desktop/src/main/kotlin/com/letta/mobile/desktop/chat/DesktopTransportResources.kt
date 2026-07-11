package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import computer.iroh.Endpoint
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the transport-level resources the desktop App Server gateway wires at
 * create() time — the iroh endpoint+transport pair, or the WebSocket
 * transport+HttpClient pair — so the gateway's [close] releases them on
 * retryConnection/shutdown instead of leaking channel jobs.
 *
 * Close is idempotent, failure-isolated, and bounded: each teardown step runs
 * under runCatching AND a [stepTimeoutMs] cap, so a hung QUIC/WS teardown can
 * never block the caller (or the EDT) indefinitely, and a failing/hung step
 * still lets later steps run in a live coroutine scope.
 */
internal class DesktopTransportResources internal constructor(
    private val teardownSteps: List<suspend () -> Unit>,
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
) : AutoCloseable {

    constructor(endpoint: Endpoint, transport: IrohAppServerTransport) : this(
        teardownSteps = listOf(
            { transport.close() },
            { endpoint.shutdown() },
            { endpoint.close() },
        ),
    )

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking {
            teardownSteps.forEach { step ->
                runCatching { withTimeoutOrNull(stepTimeoutMs) { step() } }
            }
        }
    }

    companion object {
        const val STEP_TIMEOUT_MS = 5_000L

        fun forWebSocket(
            transport: KtorAppServerWebSocketTransport,
            httpClient: HttpClient,
        ): DesktopTransportResources = DesktopTransportResources(
            teardownSteps = listOf(
                { transport.close() },
                { httpClient.close() },
            ),
        )
    }
}
