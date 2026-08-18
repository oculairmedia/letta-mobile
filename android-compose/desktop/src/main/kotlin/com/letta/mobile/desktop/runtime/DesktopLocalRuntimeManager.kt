package com.letta.mobile.desktop.runtime

import java.io.BufferedReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
}

internal fun interface DesktopRuntimeProcessLauncher {
    fun launch(command: List<String>, environment: Map<String, String>): DesktopRuntimeProcess
}

internal class DesktopLocalRuntimeManager(
    private val installationProvider: () -> DesktopLettaCodeInstallation?,
    private val backendDirectory: () -> java.io.File,
    private val processLauncher: DesktopRuntimeProcessLauncher,
    private val logLine: (String) -> Unit,
    private val readyTimeoutMs: Long = 30_000L,
    private val stopTimeoutMs: Long = 5_000L,
) : AutoCloseable {
    private var child: DesktopRuntimeProcess? = null
    private var childUrl: String? = null

    @Synchronized
    fun ensureStarted(): String {
        val existing = child
        if (existing?.isAlive == true) return checkNotNull(childUrl)
        close()
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
        val process = processLauncher.launch(
            command,
            mapOf(
                "LETTA_LOCAL_BACKEND_EXPERIMENTAL" to "1",
                "LETTA_LOCAL_BACKEND_DIR" to backendDir.absolutePath,
            ),
        )
        child = process
        val queue = LinkedBlockingQueue<RuntimeOutputEvent>()
        drain("stderr", process.stderr)
        Thread {
            process.stdout.useLines { lines ->
                lines.forEach { line ->
                    logLine("[stdout] $line")
                    queue.put(RuntimeOutputEvent.Line(line))
                }
            }
            queue.put(RuntimeOutputEvent.End)
        }.apply { isDaemon = true; name = "letta-local-runtime-stdout"; start() }

        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs)
            while (System.nanoTime() < deadline) {
                val remaining = deadline - System.nanoTime()
                val event = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                if (event === RuntimeOutputEvent.End) break
                val url = parseDesktopRuntimeListenUrl((event as RuntimeOutputEvent.Line).value)
                if (url != null) {
                    childUrl = url
                    return url
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
        val exitCode = process.exitCodeOrNull
        close()
        if (exitCode != null) error("Bundled Letta Code runtime exited before ready (exit=$exitCode)")
        error("Bundled Letta Code runtime did not announce a listen URL")
    }

    @Synchronized
    override fun close() {
        val process = child ?: return
        child = null
        childUrl = null
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

    private fun drain(name: String, reader: BufferedReader) {
        Thread {
            reader.useLines { lines -> lines.forEach { logLine("[$name] $it") } }
        }.apply { isDaemon = true; this.name = "letta-local-runtime-$name"; start() }
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
