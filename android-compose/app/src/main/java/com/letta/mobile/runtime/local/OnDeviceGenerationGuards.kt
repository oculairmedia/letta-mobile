package com.letta.mobile.runtime.local

import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * letta-mobile-by4vm.121: what ends an on-device generation from outside the engine.
 *
 * - The client went away. letta-code aborts its provider request when a turn is cancelled (and its
 *   fetch gives up on its own after 300 s without response headers); the bridge sees the socket
 *   close and cancels the generation, so the single engine is free for the next request at once
 *   instead of generating for nobody.
 * - The per-request timeout fired: a backstop for a run no other bound ended.
 */
internal class OnDeviceGenerationGuards(
    private val executor: ExecutorService,
    private val timer: ScheduledExecutorService,
) {
    fun <T> guard(socket: Socket, generation: OnDeviceGeneration, block: () -> T): T {
        val watcher = executor.submit { watchForDisconnect(socket, generation) }
        val timeout = timer.schedule(
            { generation.cancel(OnDeviceStopReason.Timeout) },
            generation.limits.timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        try {
            return block()
        } finally {
            // Later cancels are no-ops: the watcher ends when the reply's socket closes.
            generation.finish()
            timeout.cancel(false)
            watcher.cancel(true)
        }
    }

    /**
     * The request body is fully read before generation starts, so the next read on the socket
     * blocks until the client sends more (it never does: one request per connection) or closes:
     * end of stream or an error means the client is gone.
     */
    private fun watchForDisconnect(socket: Socket, generation: OnDeviceGeneration) {
        val input = runCatching { socket.getInputStream() }.getOrNull()
        while (input != null && !Thread.currentThread().isInterrupted) {
            val read = try {
                input.read()
            } catch (_: IOException) {
                -1
            }
            if (read == -1) break
        }
        if (generation.cancel(OnDeviceStopReason.ClientDisconnected)) {
            logLiteRtInfo("bridge_client_disconnected", "action=cancel_generation")
        }
    }
}
