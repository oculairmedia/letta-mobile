package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasRelayAttachment
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.canvas.CanvasRelayDial
import com.letta.mobile.data.canvas.CanvasRelayLink
import com.letta.mobile.data.model.HostId
import computer.iroh.Connection
import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Carries an app's [CanvasRelayClient] to its host over Iroh: the shared [CanvasRelayAttachment]
 * over relay streams opened beside the app's one host connection.
 *
 * Every app already holds one Iroh connection, to its host (the App Server's Iroh node). While that
 * connection is ready ([attach]), the attachment keeps one [IrohCanvasRelay.CANVAS_RELAY_ALPN]
 * connection open to the same host from the same local endpoint - no endpoint or accept loop of its
 * own - and dials again with backoff when it drops.
 */
class IrohCanvasRelayClient(
    private val scope: CoroutineScope,
    val relay: CanvasRelayClient,
) {
    private val attachment = CanvasRelayAttachment(scope, relay)

    /**
     * Follows [readyHandle], the host connection while it is ready, replacing whatever was attached
     * before (a new session graph, a changed server). Ends with [attachScope].
     */
    fun attach(readyHandle: Flow<IrohConnectionHandle?>, attachScope: CoroutineScope = scope): Job =
        attachment.attach(readyHandle.map { handle -> handle?.openConnection?.let(::relayDial) }, attachScope)

    /** The app is on a backend with no Iroh host: every canvas stays on this device, and says so. */
    fun detach() = attachment.detach()

    private fun relayDial(open: suspend (ByteArray) -> Connection) = CanvasRelayDial {
        val connection = open(IrohCanvasRelay.CANVAS_RELAY_ALPN)
        val stream = runCatching { connection.openBi() }.getOrElse { failure ->
            runCatching { connection.close() }
            throw failure
        }
        IrohCanvasRelayLink(connection, stream.send(), stream.recv())
    }
}

/** A [CanvasRelayLink] over one Iroh relay connection's bidirectional stream. */
private class IrohCanvasRelayLink(
    private val connection: Connection,
    private val sendStream: SendStream,
    private val recvStream: RecvStream,
) : CanvasRelayLink {
    override val hostId: HostId = HostId(IrohDiagnostics.endpointIdHex(connection.remoteId()))

    override suspend fun send(frame: String) = CanvasRelayFraming.write(sendStream, frame)

    override suspend fun receive(): String? = CanvasRelayFraming.read(recvStream)

    override suspend fun close() {
        runCatching { connection.close() }
    }
}
