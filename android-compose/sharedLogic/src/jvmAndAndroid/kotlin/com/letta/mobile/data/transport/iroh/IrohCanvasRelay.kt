package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasRelayDecoded
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasRelayMessage
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasRelayStore
import com.letta.mobile.util.Telemetry
import computer.iroh.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The host's side of shared canvases over Iroh: one [CANVAS_RELAY_ALPN] connection per app, fed
 * into a [CanvasRelayHost] over [store]. The host endpoint accepts that ALPN only from a peer with an
 * authenticated App Server connection and hands it here with that peer's node id as its origin; no
 * accept loop of its own.
 */
class IrohCanvasRelay(
    private val scope: CoroutineScope,
    store: CanvasRelayStore,
) {
    @Volatile
    private var hostId: String = ""
    private val host = CanvasRelayHost(store, hostId = { hostId })
    private val reaper: Job = scope.launch {
        while (isActive) {
            delay(PRESENCE_REAP_MS)
            runCatching { host.reapPresence() }
        }
    }

    val alpns: List<ByteArray> = listOf(CANVAS_RELAY_ALPN)

    fun handles(alpn: ByteArray): Boolean = alpn.contentEquals(CANVAS_RELAY_ALPN)

    /**
     * Serves an accepted canvas connection from [origin] (the authenticated peer) on host [hostNodeId];
     * false when [alpn] is not the canvas protocol.
     */
    fun accept(alpn: ByteArray, connection: Connection, origin: String, hostNodeId: String): Boolean {
        if (!handles(alpn)) return false
        hostId = hostNodeId
        scope.launch { serve(connection, origin) }
        return true
    }

    private suspend fun serve(connection: Connection, origin: String) {
        val stream = try {
            connection.acceptBi()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.event("CanvasRelay", "stream.failed", "origin" to origin, "error" to (e.message ?: e.toString()))
            return
        }
        val send = stream.send()
        val recv = stream.recv()
        val writes = Mutex()
        val session = host.connect(origin) { message ->
            writes.withLock { CanvasRelayFraming.write(send, CanvasRelayProtocol.encode(message)) }
        }
        Telemetry.event("CanvasRelay", "app.connected", "origin" to origin, "generation" to session.generation)
        try {
            while (!session.closed) {
                val text = CanvasRelayFraming.read(recv) ?: break
                when (val decoded = CanvasRelayProtocol.decode(text)) {
                    is CanvasRelayDecoded.Message -> session.receive(decoded.message)
                    is CanvasRelayDecoded.UnsupportedVersion -> {
                        refuse(session, "unsupported canvas relay protocol v${decoded.version}; this host speaks v${CanvasRelayProtocol.VERSION}")
                        break
                    }
                    is CanvasRelayDecoded.Malformed -> {
                        refuse(session, "malformed frame: ${decoded.reason}")
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.event("CanvasRelay", "app.error", "origin" to origin, "error" to (e.message ?: e.toString()))
        } finally {
            withContext(NonCancellable) {
                session.close()
                runCatching { send.finish() }
                runCatching { connection.close() }
            }
            Telemetry.event("CanvasRelay", "app.disconnected", "origin" to origin, "generation" to session.generation)
        }
    }

    private suspend fun refuse(session: CanvasRelayHost.Session, reason: String) {
        Telemetry.event("CanvasRelay", "app.refused", "origin" to session.origin, "reason" to reason)
        session.deliver(CanvasRelayMessage.Refused(reason))
    }

    companion object {
        /** One connection per app carries its canvas ops and cursors; the version is in every frame. */
        val CANVAS_RELAY_ALPN: ByteArray = "meridian/canvas-relay/1".encodeToByteArray()
        private const val PRESENCE_REAP_MS = 2_000L
    }
}
