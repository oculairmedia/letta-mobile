package com.letta.mobile.desktop.runtime

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal data class DesktopLettaCodeInstallation(
    val nodeExecutable: File,
    val lettaEntryPoint: File,
)

internal object DesktopLettaCodeRuntimeLocator {
    private const val NODE_PROPERTY = "letta.desktop.runtime.node"
    private const val LETTA_JS_PROPERTY = "letta.desktop.runtime.lettaJs"
    private const val NODE_ENV = "LETTA_DESKTOP_RUNTIME_NODE"
    private const val LETTA_JS_ENV = "LETTA_DESKTOP_RUNTIME_JS"

    fun locate(): DesktopLettaCodeInstallation? {
        explicitInstallation()?.let { return it.validatedOrNull() }
        val resourcesRoot = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: return null
        return sequenceOf(
            File(resourcesRoot, "letta-code-runtime"),
            File(resourcesRoot, "windows/letta-code-runtime"),
        ).map { runtimeRoot ->
            DesktopLettaCodeInstallation(
                nodeExecutable = File(runtimeRoot, "node.exe"),
                lettaEntryPoint = File(runtimeRoot, "node_modules/@letta-ai/letta-code/letta.js"),
            )
        }.mapNotNull { it.validatedOrNull() }.firstOrNull()
    }

    private fun explicitInstallation(): DesktopLettaCodeInstallation? {
        val node = System.getProperty(NODE_PROPERTY)?.takeIf(String::isNotBlank)
            ?: System.getenv(NODE_ENV)?.takeIf(String::isNotBlank)
        val lettaJs = System.getProperty(LETTA_JS_PROPERTY)?.takeIf(String::isNotBlank)
            ?: System.getenv(LETTA_JS_ENV)?.takeIf(String::isNotBlank)
        return if (node != null && lettaJs != null) {
            DesktopLettaCodeInstallation(File(node), File(lettaJs))
        } else {
            null
        }
    }

    private fun DesktopLettaCodeInstallation.validatedOrNull(): DesktopLettaCodeInstallation? =
        takeIf { it.nodeExecutable.isFile && it.lettaEntryPoint.isFile }
}

/** Owns the bundled local Letta Code child for the lifetime of the desktop process. */
internal object DesktopLocalRuntimeHost : AutoCloseable {
    private const val READY_TIMEOUT_MS = 30_000L
    private const val STOP_TIMEOUT_MS = 5_000L
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
    private val listeningPattern = Regex("""Listening on (ws://\S+)""")
    private var child: Process? = null
    private var childUrl: String? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread(::close, "letta-desktop-runtime-shutdown"))
    }

    fun backendDirectory(): File =
        File(System.getProperty("user.home"), ".letta-mobile/local-backend")

    @Synchronized
    fun ensureStarted(): String {
        val existing = child
        if (existing?.isAlive == true) return checkNotNull(childUrl)
        close()
        val installation = DesktopLettaCodeRuntimeLocator.locate()
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
        val process = ProcessBuilder(command).apply {
            environment()["LETTA_LOCAL_BACKEND_EXPERIMENTAL"] = "1"
            environment()["LETTA_LOCAL_BACKEND_DIR"] = backendDir.absolutePath
        }.start()
        child = process
        val logFile = File(System.getProperty("user.home"), ".letta-mobile/logs/local-runtime.log")
        val queue = LinkedBlockingQueue<Any>()
        val eof = Any()
        drain("stderr", process.errorStream.bufferedReader(), logFile)
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendLog(logFile, "[stdout] $line")
                    queue.put(line)
                }
            }
            queue.put(eof)
        }.apply { isDaemon = true; name = "letta-local-runtime-stdout"; start() }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val item = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            if (item === eof) break
            val line = item as String
            listeningPattern.find(line)?.let { match ->
                childUrl = match.groupValues[1]
                return checkNotNull(childUrl)
            }
        }
        close()
        error("Bundled Letta Code runtime did not announce a listen URL")
    }

    @Synchronized
    override fun close() {
        val process = child ?: return
        child = null
        childUrl = null
        if (!process.isAlive) return
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun drain(name: String, reader: BufferedReader, logFile: File) {
        Thread {
            reader.useLines { lines -> lines.forEach { appendLog(logFile, "[$name] $it") } }
        }.apply { isDaemon = true; this.name = "letta-local-runtime-$name"; start() }
    }

    @Synchronized
    private fun appendLog(file: File, line: String) {
        file.parentFile.mkdirs()
        if (file.length() > MAX_LOG_BYTES) {
            val previous = File(file.parentFile, "${file.name}.previous")
            previous.delete()
            file.renameTo(previous)
        }
        file.appendText("$line\n")
    }
}
