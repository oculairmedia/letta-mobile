package com.letta.mobile.appservercli

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Production [AppServerSupervisor.ProcessController] backed by real
 * ProcessBuilder / ProcessHandle (letta-mobile-lgns8.6). JVM-only.
 *
 * Spawns the App Server process, drains its combined stdout/stderr into a bounded
 * ring buffer for diagnostics, and — critically — destroys the entire process
 * *tree* on shutdown via [ProcessHandle.descendants] so no orphaned Node
 * children survive ("graceful termination ... leaves no descendants").
 */
class ProcessHandleController(
    private val command: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val diagnosticBufferBytes: Int = 64 * 1024,
) : AppServerSupervisor.ProcessController {

    private var process: Process? = null
    private val diagBuffer = BoundedByteBuffer(diagnosticBufferBytes)
    private var drainThread: Thread? = null

    override fun spawn() {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment().putAll(environment)
        val p = pb.start()
        process = p
        // Drain combined output on a daemon thread so the pipe never blocks the
        // child and we always have recent output for diagnostics.
        drainThread = Thread {
            val buf = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = try { stream.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                diagBuffer.append(buf, n)
            }
        }.apply { isDaemon = true; name = "appserver-output-drain"; start() }
    }

    override fun exitCodeOrNull(): Int? {
        val p = process ?: return null
        return if (p.isAlive) null else p.exitValue()
    }

    override fun terminateGracefully() {
        process?.destroy() // SIGTERM on POSIX
    }

    override fun destroyTree() {
        val p = process ?: return
        // Kill descendants first so nothing re-parents to init, then the root.
        p.toHandle().descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
        runCatching { p.waitFor(2, TimeUnit.SECONDS) }
    }

    override fun drainDiagnostics(limit: Int): String = diagBuffer.snapshotUtf8().take(limit)

    /** Fixed-capacity byte ring buffer keeping the most recent output. */
    private class BoundedByteBuffer(private val capacity: Int) {
        private val out = ByteArrayOutputStream()

        @Synchronized
        fun append(bytes: ByteArray, len: Int) {
            out.write(bytes, 0, len)
            if (out.size() > capacity) {
                val all = out.toByteArray()
                out.reset()
                out.write(all, all.size - capacity, capacity)
            }
        }

        @Synchronized
        fun snapshotUtf8(): String = out.toByteArray().decodeToString()
    }
}

/**
 * HTTP `/readyz` readiness probe. Returns true on any 2xx from the readiness
 * endpoint derived from a `ws://host:port` App Server listen URL.
 */
class HttpReadinessProbe(
    listenUrl: String,
    path: String = "/readyz",
    private val timeout: Duration = Duration.ofSeconds(2),
) : AppServerSupervisor.ReadinessProbe {

    private val readyUri: URI = toHttpReadyUri(listenUrl, path)
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    override fun isReady(): Boolean {
        return try {
            val req = HttpRequest.newBuilder(readyUri).timeout(timeout).GET().build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            resp.statusCode() in 200..299
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Convert a ws://host:port listen URL to its http(s) /readyz URI. */
        fun toHttpReadyUri(listenUrl: String, path: String): URI {
            val u = URI(listenUrl)
            val scheme = when (u.scheme?.lowercase()) {
                "wss", "https" -> "https"
                else -> "http"
            }
            val host = u.host ?: "127.0.0.1"
            val port = if (u.port > 0) u.port else 4500
            return URI("$scheme://$host:$port$path")
        }
    }
}
