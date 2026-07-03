package com.letta.mobile.avatar.rendererweb

import com.letta.mobile.avatar.core.AvatarCapabilities
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarRuntimeState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Real-socket integration tests for the loopback host. runBlocking (not
 * runTest): virtual time would fire the timeouts while blocked on real IO.
 */
class AvatarWebHostTest {
    private fun client() = HttpClient(CIO) { install(WebSockets) }

    @Test
    fun servesTheBundledFrontendFromClasspathResources() = runBlocking<Unit> {
        AvatarWebHost().started().use { host ->
            client().use { http ->
                val page = http.get("${host.baseUrl}/")
                assertEquals(HttpStatusCode.OK, page.status)
                assertTrue("Avatar dev harness" in page.bodyAsText())

                val lib = http.get("${host.baseUrl}/vendor/three-vrm.module.min.js")
                assertEquals(HttpStatusCode.OK, lib.status)

                val renderer = http.get("${host.baseUrl}/avatar-renderer.js")
                assertEquals(HttpStatusCode.OK, renderer.status)
                assertTrue("createAvatarRenderer" in renderer.bodyAsText())
            }
        }
    }

    @Test
    fun servesRegisteredAssetsAndRejectsUnknownTokens() = runBlocking<Unit> {
        AvatarWebHost().started().use { host ->
            val file = Files.createTempFile("avatar-host-test", ".vrm")
            try {
                Files.write(file, byteArrayOf(1, 2, 3, 4))
                val url = host.assetUrl(file)

                client().use { http ->
                    assertEquals(HttpStatusCode.OK, http.get(url).status)
                    assertEquals(
                        HttpStatusCode.NotFound,
                        http.get("${host.baseUrl}/asset/nope").status,
                    )
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun bridgesTheFullLoadHandshakeOverARealWebSocket() = runBlocking<Unit> {
        AvatarWebHost().started().use { host ->
            val runtime = WebAvatarRuntime(host.transport, loadTimeoutMillis = 10_000)
            val model = AvatarModel(
                id = "a",
                displayName = "A",
                uri = "${host.baseUrl}/asset/whatever",
                format = AvatarFormat.VRM_1,
            )

            client().use { http ->
                http.webSocket("${host.baseUrl.replace("http", "ws")}/bridge") {
                    // Fake renderer page: announce ready (as the page does on
                    // connect), answer the load command with loaded.
                    send(
                        Frame.Text(
                            AvatarWireProtocol.encodeEvent(
                                AvatarRendererEvent.Ready(AvatarWireProtocol.VERSION),
                            ),
                        ),
                    )

                    val loadJob = async { runtime.load(model) }

                    val command = withTimeout(10_000) {
                        var received: AvatarRendererCommand? = null
                        while (received !is AvatarRendererCommand.LoadAvatar) {
                            val frame = incoming.receive()
                            if (frame is Frame.Text) {
                                received = AvatarWireProtocol.decodeCommand(frame.readText())
                            }
                        }
                        received
                    }
                    assertEquals(model.uri, command.url)

                    send(
                        Frame.Text(
                            AvatarWireProtocol.encodeEvent(
                                AvatarRendererEvent.AvatarLoaded(
                                    requestId = command.requestId,
                                    capabilities = AvatarCapabilities(supportsHumanoid = true),
                                ),
                            ),
                        ),
                    )
                    loadJob.await()
                }
            }

            val ready = assertIs<AvatarRuntimeState.Ready>(runtime.state.value)
            assertTrue(ready.capabilities.supportsHumanoid)
        }
    }
}

private inline fun <T> AvatarWebHost.use(block: (AvatarWebHost) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
