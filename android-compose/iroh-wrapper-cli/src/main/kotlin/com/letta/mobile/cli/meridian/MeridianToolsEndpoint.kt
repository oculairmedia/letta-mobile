package com.letta.mobile.cli.meridian

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.meridian.MeridianCommandRouter
import com.letta.mobile.data.meridian.PerConversationRateLimiter
import com.letta.mobile.data.meridian.endpoint.MeridianCallerBinder
import com.letta.mobile.data.meridian.endpoint.MeridianLiveCalls
import com.letta.mobile.data.meridian.endpoint.MeridianToolsService
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWire
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * The wrapper's local `meridian/tools/1` endpoint (letta-mobile-jna0o.4): the Meridian command
 * router over [registry] (the same registry the controller advertises to runtimes), reachable from
 * the agent's shell through the `meridian` shim.
 *
 * - Listens on the Unix socket ([MeridianToolsConfig.socketPath], mode 0660). With
 *   [MeridianToolsTransport.AUTO] a socket that cannot be bound falls back to loopback TCP, whose
 *   port and bearer token go to [MeridianToolsConfig.endpointFile] (mode 0600, the App Server
 *   user's).
 * - Binds callers to live `meridian` shell calls seen on [frames] (the App Server frames this host
 *   relays), or, with the `env-scoped` fallback, to the shell env's scope.
 * - Rate-limits tool-running calls per conversation and keeps the router's size caps.
 *
 * Off unless `--agent-tools-mode cli`: [start] then does nothing, so the default host is unchanged.
 */
class MeridianToolsEndpoint(
    private val config: MeridianToolsConfig,
    private val registry: ExternalToolRegistry,
    private val frames: Flow<AppServerReceivedFrame>,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** A bound listener and the bearer token it requires (TCP only). */
    internal data class Listener(val channel: ServerSocketChannel, val token: String?, val description: String)

    /** Starts the endpoint on [scope]; null when the mode is not `cli` or nothing could be bound. */
    suspend fun start(scope: CoroutineScope): Job? {
        if (!config.enabled) return null
        val listener = withContext(Dispatchers.IO) { bind() } ?: return null
        val liveCalls = MeridianLiveCalls(nowMs)
        val service = MeridianToolsService(router(), MeridianCallerBinder(config.binding, liveCalls), listener.token)
        scope.launch { frames.collect(liveCalls::observe) }
        log(
            "[meridian-tools] serving ${MeridianToolsWire.PROTOCOL} on ${listener.description} " +
                "(caller binding: ${config.binding.wire}, ${config.callsPerMinute} calls/min/conversation)",
        )
        return scope.launch { MeridianToolsSocketServer(service, log).serve(listener.channel) }
    }

    private fun router() = MeridianCommandRouter(
        registry = registry,
        rateLimiter = PerConversationRateLimiter(config.callsPerMinute, WINDOW_MS, nowMs),
        // No file reads on the host's side: the shim reads `--input-file` as the agent's own user
        // and sends it as stdin, so the endpoint never becomes a read primitive for another user.
        inputFiles = null,
    )

    internal fun bind(): Listener? = when (config.transport) {
        MeridianToolsTransport.UNIX -> unix().getOrElse { failed("Unix socket ${config.socketPath}", it) }
        MeridianToolsTransport.TCP -> tcp().getOrElse { failed("loopback TCP", it) }
        MeridianToolsTransport.AUTO -> unix().getOrElse { error ->
            log("[meridian-tools] Unix socket ${config.socketPath} unavailable (${error.message}); falling back to loopback TCP")
            tcp().getOrElse { failed("loopback TCP", it) }
        }
    }

    private fun unix(): Result<Listener> = runCatching {
        val channel = MeridianToolsSocketServer.bindUnix(Path.of(config.socketPath))
        // A stale TCP descriptor would point the shim at a port nobody serves any more.
        Files.deleteIfExists(Path.of(config.endpointFile))
        Listener(channel, token = null, description = "unix:${config.socketPath}")
    }

    private fun tcp(): Result<Listener> = runCatching {
        val channel = MeridianToolsSocketServer.bindLoopback(config.tcpPort)
        val port = (channel.localAddress as java.net.InetSocketAddress).port
        val token = newToken()
        try {
            writeEndpointFile(Path.of(config.endpointFile), port, token)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        Listener(channel, token, "tcp:127.0.0.1:$port (token in ${config.endpointFile})")
    }

    private fun failed(what: String, error: Throwable): Listener? {
        log("[meridian-tools] DISABLED: could not bind $what (${error.message})")
        return null
    }


    companion object {
        private const val WINDOW_MS = 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val TOKEN_BYTES = 32
        private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
        private val OWNER_DIR = PosixFilePermissions.fromString("rwx------")

        internal fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Writes the TCP descriptor atomically, readable only by its owner where POSIX modes exist. */
        internal fun writeEndpointFile(file: Path, port: Int, token: String) {
            val body = buildJsonObject {
                put("protocol", MeridianToolsWire.PROTOCOL)
                put("transport", "tcp")
                put("host", "127.0.0.1")
                put("port", port)
                put("token", token)
            }.toString() + "\n"
            val parent = file.toAbsolutePath().parent
            if (!Files.isDirectory(parent)) {
                Files.createDirectories(parent)
                posix { Files.setPosixFilePermissions(parent, OWNER_DIR) }
            }
            val temp = Files.createTempFile(parent, ".tools-endpoint", ".tmp")
            posix { Files.setPosixFilePermissions(temp, OWNER_ONLY) }
            Files.writeString(temp, body)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        private fun posix(block: () -> Unit) {
            try {
                block()
            } catch (_: UnsupportedOperationException) {
                // No POSIX modes here (Windows dev boxes); the directory's own ACL applies.
            }
        }
    }
}
