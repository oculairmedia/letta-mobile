package com.letta.mobile.web.iroh

import com.letta.mobile.data.canvas.CanvasRelayLink
import com.letta.mobile.data.model.HostId
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-ckmub.17.9: the browser's [CanvasRelayLink], one relay stream of the session's own
 * Iroh endpoint. Only the binding: the protocol, delivery and backoff are the shared
 * [com.letta.mobile.data.canvas.CanvasRelayAttachment] and relay client desktop runs.
 *
 * Like the App Server pump, frames are read by polling the JS queue, backing off while it is empty.
 */
internal class IrohWasmCanvasRelayLink(
    override val hostId: HostId,
    private val stream: IrohWasmRelayStream,
) : CanvasRelayLink {
    override suspend fun send(frame: String) = stream.send(IrohWireFrame(frame))

    override suspend fun receive(): String? {
        var pollIntervalMs = MIN_POLL_INTERVAL_MS
        while (true) {
            stream.poll()?.let { return it.text }
            when (stream.state()) {
                IrohStreamState.CONNECTED -> {
                    delay(pollIntervalMs.milliseconds)
                    pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(MAX_POLL_INTERVAL_MS)
                }
                IrohStreamState.ERROR -> error(stream.error() ?: "Canvas relay stream failed")
                IrohStreamState.ENDED -> return null
            }
        }
    }

    override suspend fun close() = stream.close()

    private companion object {
        const val MIN_POLL_INTERVAL_MS = 10L
        const val MAX_POLL_INTERVAL_MS = 100L
    }
}
