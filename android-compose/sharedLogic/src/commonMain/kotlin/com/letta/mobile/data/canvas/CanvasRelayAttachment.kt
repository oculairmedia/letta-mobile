package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.HostId
import com.letta.mobile.util.Telemetry
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
 * One open canvas relay stream to the host, as a platform binding carries it: [CanvasRelayProtocol]
 * text frames both ways. The JVM and Android bind it over an Iroh `Connection`, the browser over its
 * wasm endpoint; what the frames say is [CanvasRelayClient]'s business.
 */
interface CanvasRelayLink {
    /** The host's identity (its Iroh node id), which scopes this app's cursors into its logs. */
    val hostId: HostId

    suspend fun send(frame: String)

    /** The next frame's text, or null once the stream has ended. */
    suspend fun receive(): String?

    /** Ends this stream; the connection it was opened beside stays up. */
    suspend fun close()
}

/** How a binding opens a [CanvasRelayLink] to its host: on the endpoint of its authenticated connection. */
fun interface CanvasRelayDial {
    suspend fun open(): CanvasRelayLink
}

/**
 * Carries an app's [CanvasRelayClient] to its host over whatever [CanvasRelayDial] its binding offers.
 *
 * While the app's host connection is ready ([attach] follows it), this keeps one relay stream open
 * to the same host, dialling again with backoff when it drops. The host admits it only for this
 * app's authenticated identity and relays between everyone on it.
 */
class CanvasRelayAttachment(
    private val scope: CoroutineScope,
    val relay: CanvasRelayClient,
) {
    private var attached: Job? = null

    /**
     * Follows [dials], the way to the host while it is ready (null while it is not), replacing
     * whatever was attached before. Ends with [attachScope]. While attached, a canvas with no host
     * in reach reports itself offline, never local-only.
     */
    fun attach(dials: Flow<CanvasRelayDial?>, attachScope: CoroutineScope = scope): Job {
        attached?.cancel()
        return attachScope.launch {
            relay.expectHost(true)
            dials.collectLatest { dial -> dial?.let { keepConnected(it) } }
        }.also { attached = it }
    }

    /** The app is on a backend with no Iroh host: every canvas stays on this device, and says so. */
    fun detach() {
        attached?.cancel()
        attached = null
        scope.launch { relay.expectHost(false) }
    }

    private suspend fun keepConnected(dial: CanvasRelayDial) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val link = runCatching { dial.open() }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                Telemetry.event(TAG, "host.dial.failed", "error" to failure.describe(), "retryMs" to backoffMs)
                null
            }
            if (link != null && serve(link)) backoffMs = INITIAL_BACKOFF_MS
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Runs the relay over [link] until it ends; true when it ended cleanly. */
    private suspend fun serve(link: CanvasRelayLink): Boolean {
        val hostId = link.hostId.value
        Telemetry.event(TAG, "host.connected", "hostId" to hostId)
        val ended = try {
            runCatching { relay.run(link.asRelayConnection()) }
        } finally {
            withContext(NonCancellable) { runCatching { link.close() } }
        }
        ended.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            Telemetry.event(TAG, "host.connection.failed", "hostId" to hostId, "error" to failure.describe())
            return false
        }
        Telemetry.event(TAG, "host.disconnected", "hostId" to hostId)
        return true
    }

    private fun Throwable.describe(): String = message ?: toString()

    private companion object {
        const val TAG = "CanvasRelayClient"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}

/**
 * [this] link as the [CanvasRelayConnection] the relay drives: messages encoded one frame each,
 * writes serialized; a host on another protocol version reads as a refusal.
 */
fun CanvasRelayLink.asRelayConnection(): CanvasRelayConnection = LinkedRelayConnection(this)

private class LinkedRelayConnection(private val link: CanvasRelayLink) : CanvasRelayConnection {
    private val writes = Mutex()

    override val hostId: HostId get() = link.hostId

    override suspend fun send(message: CanvasRelayMessage) {
        writes.withLock { link.send(CanvasRelayProtocol.encode(message)) }
    }

    override suspend fun receive(): CanvasRelayMessage? {
        val text = link.receive() ?: return null
        return when (val decoded = CanvasRelayProtocol.decode(text)) {
            is CanvasRelayDecoded.Message -> decoded.message
            is CanvasRelayDecoded.UnsupportedVersion -> CanvasRelayMessage.Refused(
                "The host speaks canvas relay v${decoded.version}; this app speaks v${CanvasRelayProtocol.VERSION}",
            )
            is CanvasRelayDecoded.Malformed -> null
        }
    }
}
