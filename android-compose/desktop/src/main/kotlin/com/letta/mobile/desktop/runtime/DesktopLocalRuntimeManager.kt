package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.runtime.supervisor.RuntimeExit
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface DesktopRuntimeProcessHandle {
    val isAlive: Boolean
    fun destroy()
    fun destroyForcibly()
}

internal interface DesktopRuntimeProcess : DesktopRuntimeProcessHandle {
    val stdout: BufferedReader
    val stderr: BufferedReader
    val descendants: List<DesktopRuntimeProcessHandle>
    val exitCodeOrNull: Int?
    fun waitFor(timeoutMs: Long): Boolean

    /** Calls [callback] once, on some thread, when the process ends (exit code when known). */
    fun onExit(callback: (exitCode: Int?) -> Unit)
}

/** What to launch: the command, extra environment, and the working directory (null = inherit). */
internal data class DesktopRuntimeLaunchSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File? = null,
)

internal fun interface DesktopRuntimeProcessLauncher {
    fun launch(spec: DesktopRuntimeLaunchSpec): DesktopRuntimeProcess
}

/** Extra environment and working directory for the next spawn (letta-mobile-bzvro.5, F05). */
internal data class DesktopRuntimeLaunchContext(
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: File? = null,
)

/** Child lifecycle callbacks the supervisor listens to (letta-mobile-bzvro.3, F03). */
internal interface DesktopRuntimeEvents {
    /** A new child announced [url] and is ready. */
    fun onStarted(url: String) {}

    /** A child ended; [RuntimeExit.intentional] when we stopped it. */
    fun onExit(exit: RuntimeExit) {}

    fun onStderr(line: String) {}
}

/** The child died or stayed silent before announcing its listen URL. */
internal class DesktopRuntimeStartException(message: String, val exitCode: Int?) : IllegalStateException(message)

internal class DesktopLocalRuntimeManager(
    private val installationProvider: () -> DesktopLettaCodeInstallation?,
    private val backendDirectory: () -> File,
    private val processLauncher: DesktopRuntimeProcessLauncher,
    private val logLine: (String) -> Unit,
    private val readyTimeoutMs: Long = 30_000L,
    private val stopTimeoutMs: Long = 5_000L,
    private val launchContext: () -> DesktopRuntimeLaunchContext = { DesktopRuntimeLaunchContext() },
) : AutoCloseable {
    private var child: DesktopRuntimeProcess? = null
    private var childUrl: String? = null
    private var childStoppedByUs: AtomicBoolean? = null

    /** Set once by the supervisor before the first start. */
    @Volatile
    var events: DesktopRuntimeEvents = object : DesktopRuntimeEvents {}

    val isAlive: Boolean
        @Synchronized get() = child?.isAlive == true

    @Synchronized
    fun ensureStarted(): String {
        val existing = child
        if (existing?.isAlive == true) return checkNotNull(childUrl)
        close()
        val process = launchChild()
        val stoppedByUs = AtomicBoolean(false)
        child = process
        childStoppedByUs = stoppedByUs
        drainStderr(process.stderr)
        val queue = pumpStdout(process)

        val url = try {
            awaitListenUrl(queue)
        } catch (error: Throwable) {
            close()
            throw error
        }
        if (url != null) {
            childUrl = url
            events.onStarted(url)
            // Watch exits only once ready: a child that dies before announcing its URL is
            // reported by the exception below, never counted twice.
            process.onExit { code -> events.onExit(RuntimeExit(exitCode = code, intentional = stoppedByUs.get())) }
            return url
        }
        val exitCode = process.exitCodeOrNull
        close()
        if (exitCode != null) throw DesktopRuntimeStartException("Bundled Letta Code runtime exited before ready (exit=$exitCode)", exitCode)
        throw DesktopRuntimeStartException("Bundled Letta Code runtime did not announce a listen URL", null)
    }

    @Synchronized
    override fun close() {
        val process = child ?: return
        childStoppedByUs?.set(true)
        child = null
        childUrl = null
        childStoppedByUs = null
        val descendants = process.descendants
        descendants.filter { it.isAlive }.forEach { it.destroy() }
        if (process.isAlive) process.destroy()
        if (process.isAlive && !process.waitFor(stopTimeoutMs)) {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(stopTimeoutMs)
        } else {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }

    private fun launchChild(): DesktopRuntimeProcess {
        val installation = installationProvider()
            ?: error("The bundled Letta Code runtime is missing from this desktop distribution")
        val backendDir = backendDirectory().apply { mkdirs() }
        val command = listOf(
            installation.nodeExecutable.absolutePath,
            installation.lettaEntryPoint.absolutePath,
            "server",
            "--backend",
            "local",
            "--listen",
            "ws://127.0.0.1:0",
        )
        val context = launchContext()
        return processLauncher.launch(
            DesktopRuntimeLaunchSpec(
                command = command,
                environment = context.environment + mapOf(
                    "LETTA_LOCAL_BACKEND_EXPERIMENTAL" to "1",
                    "LETTA_LOCAL_BACKEND_DIR" to backendDir.absolutePath,
                ),
                workingDirectory = context.workingDirectory,
            ),
        )
    }

    private fun pumpStdout(process: DesktopRuntimeProcess): LinkedBlockingQueue<RuntimeOutputEvent> {
        val queue = LinkedBlockingQueue<RuntimeOutputEvent>()
        Thread {
            process.stdout.useLines { lines ->
                lines.forEach { line ->
                    logLine("[stdout] $line")
                    queue.put(RuntimeOutputEvent.Line(line))
                }
            }
            queue.put(RuntimeOutputEvent.End)
        }.apply { isDaemon = true; name = "letta-local-runtime-stdout"; start() }
        return queue
    }

    /** The announced listen URL, or null when stdout ended or [readyTimeoutMs] passed first. */
    private fun awaitListenUrl(queue: LinkedBlockingQueue<RuntimeOutputEvent>): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs)
        while (System.nanoTime() < deadline) {
            val event = queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            if (event !is RuntimeOutputEvent.Line) return null
            parseDesktopRuntimeListenUrl(event.value)?.let { return it }
        }
        return null
    }

    private fun drainStderr(reader: BufferedReader) {
        Thread {
            reader.useLines { lines ->
                lines.forEach {
                    logLine("[stderr] $it")
                    events.onStderr(it)
                }
            }
        }.apply { isDaemon = true; this.name = "letta-local-runtime-stderr"; start() }
    }

    private sealed interface RuntimeOutputEvent {
        data class Line(val value: String) : RuntimeOutputEvent
        data object End : RuntimeOutputEvent
    }
}

internal fun parseDesktopRuntimeListenUrl(line: String): String? =
    Regex("""Listening on (ws://(?:127\.0\.0\.1|localhost):\d+)\b""")
        .find(line)
        ?.groupValues
        ?.get(1)
