package com.letta.mobile.data.transport.teardown

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Releases a transport's resources (a QUIC endpoint and its transport, or a WebSocket and its
 * HTTP client) in order, once, without blocking whoever closes it.
 *
 * Close may come from a UI thread (Retry, a backend switch, quitting), so the [steps] run on
 * [teardown] instead: by default one process-wide daemon thread. Each step runs under runCatching
 * and a [stepTimeoutMs] cap, so a failing or hung step never stops the steps after it. Teardowns
 * still running when the process exits get a bounded wait in a shutdown hook, so quitting still
 * closes connections cleanly when it can.
 */
class TransportTeardown(
    private val steps: List<suspend () -> Unit>,
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
    private val teardown: Executor = defaultExecutor,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        closeAsync()
    }

    /** Starts the teardown (once) and returns it; a later call returns one already finished. */
    fun closeAsync(): Future<*> {
        if (!closed.compareAndSet(false, true)) return CompletableFuture.completedFuture(Unit)
        val task = FutureTask {
            runBlocking {
                steps.forEach { step -> runCatching { withTimeoutOrNull(stepTimeoutMs.milliseconds) { step() } } }
            }
        }
        teardown.execute(task)
        return task
    }

    companion object {
        const val STEP_TIMEOUT_MS: Long = 5_000L
        private const val EXIT_WAIT_MS: Long = 3_000L

        private val defaultExecutor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { task -> Thread(task, "transport-teardown").apply { isDaemon = true } }.also { executor ->
                Runtime.getRuntime().addShutdownHook(
                    Thread({
                        executor.shutdown()
                        executor.awaitTermination(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
                    }, "transport-teardown-at-exit"),
                )
            }
        }
    }
}
