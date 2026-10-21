package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import computer.iroh.Endpoint
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

import kotlin.time.Duration.Companion.milliseconds
/**
 * Owns the transport-level resources the desktop App Server gateway wires at
 * create() time — the iroh endpoint+transport pair, or the WebSocket
 * transport+HttpClient pair — so the gateway's [close] releases them on
 * retryConnection/shutdown instead of leaking channel jobs.
 *
 * Close is idempotent, failure-isolated, bounded and never blocks its caller: the teardown runs
 * on [DesktopTransportTeardown]'s thread, so Retry, a backend switch or quitting from the Swing
 * EDT returns at once. Each step runs under runCatching AND a [stepTimeoutMs] cap, so a hung
 * QUIC/WS teardown cannot stall the steps after it; teardowns still running when the process
 * exits get a bounded wait in a shutdown hook.
 */
internal class DesktopTransportResources internal constructor(
    private val teardownSteps: List<suspend () -> Unit>,
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
    private val teardown: Executor = DesktopTransportTeardown.executor,
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
        closeAsync()
    }

    /** Starts the teardown (once) and returns it; a second call returns an already finished one. */
    fun closeAsync(): Future<*> {
        if (!closed.compareAndSet(false, true)) return CompletableFuture.completedFuture(Unit)
        val task = FutureTask {
            runBlocking {
                teardownSteps.forEach { step ->
                    runCatching { withTimeoutOrNull(stepTimeoutMs.milliseconds) { step() } }
                }
            }
        }
        teardown.execute(task)
        return task
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

/**
 * Where transport teardowns run: one thread that lives as long as the process, off the EDT. Its
 * thread is a daemon, so it never keeps the app alive; a teardown still queued or running at exit
 * is given [EXIT_WAIT_MS] to finish, so quitting still closes connections cleanly when it can.
 */
internal object DesktopTransportTeardown {
    private const val EXIT_WAIT_MS = 3_000L

    val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "transport-teardown").apply { isDaemon = true }
    }

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread({
                executor.shutdown()
                executor.awaitTermination(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
            }, "transport-teardown-at-exit"),
        )
    }
}
