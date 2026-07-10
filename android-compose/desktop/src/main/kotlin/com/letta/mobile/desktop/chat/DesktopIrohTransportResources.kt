package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import computer.iroh.Endpoint
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the iroh endpoint + transport pair the desktop App Server gateway dials
 * at wiring time, so the gateway's [close] can release the QUIC connection and
 * the bound local endpoint (mirrors IrohChannelTransport.closeIrohResources).
 *
 * Close is idempotent and failure-isolated: each teardown step runs under
 * runCatching so a failing transport close still shuts the endpoint down.
 * The primary constructor takes suspend thunks purely for testability; real
 * wiring goes through the (endpoint, transport) convenience constructor.
 */
internal class DesktopIrohTransportResources internal constructor(
    private val closeTransport: suspend () -> Unit,
    private val shutdownEndpoint: suspend () -> Unit,
    private val closeEndpoint: suspend () -> Unit,
) : AutoCloseable {

    constructor(endpoint: Endpoint, transport: IrohAppServerTransport) : this(
        closeTransport = { transport.close() },
        shutdownEndpoint = { endpoint.shutdown() },
        closeEndpoint = { endpoint.close() },
    )

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking {
            runCatching { closeTransport() }
            runCatching { shutdownEndpoint() }
            runCatching { closeEndpoint() }
        }
    }
}
