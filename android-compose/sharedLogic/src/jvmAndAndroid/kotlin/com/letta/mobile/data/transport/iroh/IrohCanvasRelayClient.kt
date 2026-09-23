package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.canvas.CanvasRelayConnection
import com.letta.mobile.data.canvas.CanvasRelayDecoded
import com.letta.mobile.data.canvas.CanvasRelayMessage
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.util.Telemetry
import computer.iroh.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Carries an app's [CanvasRelayClient] to its host over Iroh.
 *
 * Every app already holds one Iroh connection, to its host (the App Server's Iroh node). While that
 * connection is ready ([attach]), this keeps one [IrohCanvasRelay.CANVAS_RELAY_ALPN] connection
 * open to the same host from the same local endpoint - no endpoint or accept loop of its own - and
 * dials again with backoff when it drops. The host admits it only for this app's authenticated
 * identity and relays between everyone on it.
 */
class IrohCanvasRelayClient(
    private val scope: CoroutineScope,
    val relay: CanvasRelayClient,
) {
    private var attached: Job? = null

    /**
     * Follows [readyHandle], the host connection while it is ready, replacing whatever was attached
     * before (a new session graph, a changed server). Ends with [attachScope]. While attached, a
     * canvas with no host in reach reports itself offline, never local-only.
     */
    fun attach(readyHandle: Flow<IrohConnectionHandle?>, attachScope: CoroutineScope = scope): Job {
        attached?.cancel()
        return attachScope.launch {
            relay.expectHost(true)
            readyHandle.collectLatest { handle ->
                val open = handle?.openConnection ?: return@collectLatest
                keepConnected(open)
            }
        }.also { attached = it }
    }

    /** The app is on a backend with no Iroh host: every canvas stays on this device, and says so. */
    fun detach() {
        attached?.cancel()
        attached = null
        scope.launch { relay.expectHost(false) }
    }

    private suspend fun keepConnected(open: suspend (ByteArray) -> Connection) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val connection = try {
                open(IrohCanvasRelay.CANVAS_RELAY_ALPN)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.event(TAG, "host.dial.failed", "error" to (e.message ?: e.toString()), "retryMs" to backoffMs)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            val hostId = IrohDiagnostics.endpointIdHex(connection.remoteId())
            Telemetry.event(TAG, "host.connected", "hostId" to hostId)
            try {
                val stream = connection.openBi()
                val send = stream.send()
                val recv = stream.recv()
                val writes = Mutex()
                relay.run(
                    object : CanvasRelayConnection {
                        override val hostId: String = hostId

                        override suspend fun send(message: CanvasRelayMessage) {
                            writes.withLock { CanvasRelayFraming.write(send, CanvasRelayProtocol.encode(message)) }
                        }

                        override suspend fun receive(): CanvasRelayMessage? {
                            val text = CanvasRelayFraming.read(recv) ?: return null
                            return when (val decoded = CanvasRelayProtocol.decode(text)) {
                                is CanvasRelayDecoded.Message -> decoded.message
                                is CanvasRelayDecoded.UnsupportedVersion -> CanvasRelayMessage.Refused(
                                    "The host speaks canvas relay v${decoded.version}; this app speaks v${CanvasRelayProtocol.VERSION}",
                                )
                                is CanvasRelayDecoded.Malformed -> null
                            }
                        }
                    },
                )
                backoffMs = INITIAL_BACKOFF_MS
                Telemetry.event(TAG, "host.disconnected", "hostId" to hostId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.event(TAG, "host.connection.failed", "hostId" to hostId, "error" to (e.message ?: e.toString()))
            } finally {
                withContext(NonCancellable) { runCatching { connection.close() } }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private companion object {
        const val TAG = "CanvasRelayClient"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
