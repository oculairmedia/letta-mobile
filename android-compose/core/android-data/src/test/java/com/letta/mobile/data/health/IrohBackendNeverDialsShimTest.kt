package com.letta.mobile.data.health

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.transport.WebSocketConnection
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-lgns8.10.4.1 — the "zero connections to the LettaShim" guarantee,
 * enforced structurally rather than by device observation.
 *
 * The device half of the acceptance criterion (a Pixel on an `iroh://` config
 * showing no open socket to :8291) is deferred to the E2E pass. What is pinned
 * here is everything a unit test *can* pin:
 *
 *  1. an Iroh config is classified as [BackendKind.IROH], never [BackendKind.SHIM_WS];
 *  2. [ShimBackendDetector] issues no HTTP probe at all for an Iroh config —
 *     that probe was itself a dial at the shim's address;
 *  3. [WebSocketConnection] refuses to open `/shim/v1/mobile` for an Iroh URL.
 */
class IrohBackendNeverDialsShimTest {

    @Test
    fun `iroh config classifies as IROH and never as a shim backend`() {
        for (url in IROH_URLS) {
            val config = config(url)
            assertEquals(
                "expected IROH for $url",
                BackendKind.IROH,
                config.backendKind(shimDetected = false),
            )
            // Even a stale cached "shim detected" must not downgrade an Iroh
            // config to the shim route.
            assertEquals(
                "a stale shim probe must not reclassify $url",
                BackendKind.IROH,
                config.backendKind(shimDetected = true),
            )
            assertFalse(config.backendKind().isShim)
            assertTrue(config.backendKind().usesChannelTransport)
        }
    }

    @Test
    fun `shim config still classifies as SHIM_WS so shim backends keep working`() {
        val config = config("http://localhost:8291")

        assertEquals(BackendKind.SHIM_WS, config.backendKind(shimDetected = true))
        assertEquals(BackendKind.REST, config.backendKind(shimDetected = false))
        assertTrue(config.backendKind(shimDetected = true).isShim)
    }

    @Test
    fun `detector issues no http probe for an iroh config`() = runTest {
        val apiClient = mockk<LettaApiClient>()
        val config = config("iroh://node-abc")
        val detector = ShimBackendDetector(MutableStateFlow(config), apiClient)

        assertFalse("an iroh backend is not a shim backend", detector.refreshActive())
        // The detector swallows probe failures, so "it returned false" is not
        // proof it stayed off the network. Assert the HTTP client was never
        // even asked for.
        verify { apiClient wasNot Called }
        assertEquals(BackendKind.IROH, detector.cachedActiveBackendKind())
        assertTrue(detector.cachedActiveUsesChannelTransport())
        assertFalse(detector.cachedActiveIsShimBackend())
    }

    @Test
    fun `detector issues no http probe for a local runtime config`() = runTest {
        val apiClient = mockk<LettaApiClient>()
        val config = config("http://localhost:8291").copy(mode = LettaConfig.Mode.LOCAL)
        val detector = ShimBackendDetector(MutableStateFlow(config), apiClient)

        assertFalse(detector.refreshActive())
        verify { apiClient wasNot Called }
        assertEquals(BackendKind.LOCAL_RUNTIME, detector.cachedActiveBackendKind())
    }

    @Test
    fun `websocket connection refuses to dial the shim mobile channel for iroh urls`() {
        val connection = WebSocketConnection(TestScope(), Json { ignoreUnknownKeys = true })

        for (url in IROH_URLS) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                connection.connect(
                    baseShimUrl = url,
                    token = "",
                    deviceId = "device",
                    clientVersion = "test",
                    listener = object : WebSocketListener() {},
                )
            }
            assertTrue(
                "message should name the refused shim path: ${error.message}",
                error.message.orEmpty().contains("/shim/v1/mobile"),
            )
        }
    }

    private fun config(url: String) = LettaConfig(
        id = "cfg",
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = url,
        accessToken = "token",
    )

    private companion object {
        /** Bare, plus the corrupted saved-config forms the app has shipped. */
        val IROH_URLS = listOf(
            "iroh://node-abc",
            "https://iroh://node-abc",
            "http://iroh://node-abc",
            "  iroh://node-abc",
        )
    }
}
