package com.letta.mobile.avatar.rendererweb

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Loopback-only host for the bundled avatar-web renderer. This is local IPC,
 * not a network service: it binds 127.0.0.1 exclusively, serves the frontend
 * packaged in this module's resources, exposes registered local asset files,
 * and bridges [AvatarWireProtocol] over a WebSocket at `/bridge`.
 *
 * Topology note: agent state reaches the client over its own transport
 * (iroh/HTTP); the avatar renderer is a presentation surface of the client
 * and never talks to any server. When the renderer is embedded via
 * JCEF/WebView the WebSocket disappears in favor of a direct JS bridge —
 * both are [AvatarRendererTransport]s.
 *
 * ```kotlin
 * val host = AvatarWebHost().started()
 * val runtime = WebAvatarRuntime(host.transport)
 * openBrowser(host.pageUrl)            // or point an embedded view at it
 * runtime.load(model.copy(uri = host.assetUrl(localVrmPath)))
 * ```
 */
class AvatarWebHost(
    private val requestedPort: Int = 0,
) : AutoCloseable {
    private var server: EmbeddedServer<*, *>? = null
    private val assets = ConcurrentHashMap<String, Path>()
    private val assetCounter = AtomicInteger(0)
    private val bridge = BridgeTransport()

    /** The [AvatarRendererTransport] backed by the `/bridge` WebSocket. */
    val transport: AvatarRendererTransport get() = bridge

    var baseUrl: String = ""
        private set

    /** URL the renderer page (in WS mode) should be opened at. */
    val pageUrl: String get() = "$baseUrl/?ws=1"

    /** Start serving; resolves the ephemeral port. Idempotent per instance. */
    suspend fun started(): AvatarWebHost {
        if (server != null) return this
        val engine = embeddedServer(CIO, host = LOOPBACK, port = requestedPort) {
            install(WebSockets)
            routing {
                staticResources("/", "letta-avatar-web")
                get("/asset/{token}") {
                    val path = call.parameters["token"]?.let(assets::get)
                    if (path == null || !Files.isRegularFile(path)) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respondFile(path.toFile())
                    }
                }
                webSocket("/bridge") {
                    bridge.attach(this)
                    val writer = launch {
                        for (message in bridge.outbound) {
                            send(Frame.Text(message))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) bridge.deliver(frame.readText())
                        }
                    } finally {
                        writer.cancel()
                        bridge.detach(this)
                    }
                }
            }
        }
        engine.start(wait = false)
        val port = engine.engine.resolvedConnectors().first().port
        baseUrl = "http://$LOOPBACK:$port"
        server = engine
        return this
    }

    /**
     * Expose a local file (a packed catalog asset) to the renderer; returns
     * the URL to use as the [com.letta.mobile.avatar.core.AvatarModel.uri].
     */
    fun assetUrl(path: Path): String {
        check(baseUrl.isNotEmpty()) { "Host not started" }
        val token = "a${assetCounter.incrementAndGet()}-${path.fileName}"
        assets[token] = path.toAbsolutePath()
        return "$baseUrl/asset/$token"
    }

    override fun close() {
        bridge.close()
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        server = null
    }

    /**
     * WebSocket-backed transport. Outbound commands buffer in an unbounded
     * channel, so commands sent before/between renderer connections are
     * delivered once a page attaches (the page's buffered `ready` event flows
     * the other way on connect). One renderer at a time; a new connection
     * supersedes the previous.
     */
    private class BridgeTransport : AvatarRendererTransport {
        val outbound = Channel<String>(Channel.UNLIMITED)
        private val handler = AtomicReference<(String) -> Unit> { }
        private val activeSession = AtomicReference<Any?>(null)

        fun attach(session: Any) {
            activeSession.set(session)
        }

        fun detach(session: Any) {
            activeSession.compareAndSet(session, null)
        }

        fun deliver(message: String) {
            handler.get()(message)
        }

        override fun send(message: String) {
            outbound.trySend(message)
        }

        override fun setMessageHandler(handler: (String) -> Unit) {
            this.handler.set(handler)
        }

        override fun close() {
            outbound.close()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
