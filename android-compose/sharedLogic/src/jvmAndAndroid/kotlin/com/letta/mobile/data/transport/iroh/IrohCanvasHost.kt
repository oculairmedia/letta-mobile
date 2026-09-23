package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.util.Telemetry
import computer.iroh.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The apps' side of shared canvases.
 *
 * Every app already holds one Iroh connection, to its host (the App Server's Iroh node). Canvas
 * sync and presence ride beside it: while the host connection is ready, this keeps a canvas-sync
 * and a canvas-presence connection open to the same host, from the same local endpoint, and dials
 * again with backoff when either drops. The host relays between everyone connected to it
 * ([IrohCanvasRelay]), so every app on that host shares its canvases.
 *
 * [sync] and [presence] are what canvas sessions use. With no host they still carry edits between
 * sessions in this process, as the loopback transports did.
 */
class IrohCanvasClient(
    private val scope: CoroutineScope,
    opLog: CanvasOpLog? = null,
) {
    /** Answers the host's catch-up requests from [opLog], so edits made offline reach it. */
    val sync = IrohCanvasSyncTransport(scope, opLog = opLog)
    val presence = IrohCanvasPresenceTransport(scope)

    private var attached: Job? = null

    /**
     * Follows [readyHandle], the host connection while it is ready, replacing whatever was
     * attached before (a new session graph, a changed server). Ends with [attachScope].
     */
    fun attach(readyHandle: Flow<IrohConnectionHandle?>, attachScope: CoroutineScope = scope): Job {
        attached?.cancel()
        return attachScope.launch {
            readyHandle.collectLatest { handle ->
                val open = handle?.openConnection ?: return@collectLatest
                coroutineScope {
                    launch {
                        keepConnected(open, IrohCanvasSyncTransport.CANVAS_SYNC_ALPN, "CanvasSync") {
                            sync.registerConnection(it)
                        }
                    }
                    launch {
                        keepConnected(open, IrohCanvasPresenceTransport.CANVAS_PRESENCE_ALPN, "CanvasPresence") {
                            presence.registerConnection(it)
                        }
                    }
                }
            }
        }.also { attached = it }
    }

    private suspend fun keepConnected(
        open: suspend (ByteArray) -> Connection,
        alpn: ByteArray,
        tag: String,
        register: (Connection) -> Job,
    ) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val connection = try {
                open(alpn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.event(tag, "host.dial.failed", "error" to (e.message ?: e.toString()), "retryMs" to backoffMs)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            Telemetry.event(tag, "host.connected")
            backoffMs = INITIAL_BACKOFF_MS
            val served = register(connection)
            try {
                served.join()
                Telemetry.event(tag, "host.disconnected")
            } finally {
                served.cancel()
                withContext(NonCancellable) { runCatching { connection.close() } }
            }
            delay(backoffMs)
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
