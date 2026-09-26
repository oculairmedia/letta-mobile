package com.letta.mobile.data.health

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-lgns8.10.4.1 — the "zero connections to the LettaShim" guarantee,
 * enforced structurally rather than by device observation.
 *
 * The device half of the acceptance criterion (a Pixel on an `iroh://` config
 * showing no open socket to :8291) is deferred to the E2E pass. What is pinned
 * here is that an Iroh config is classified as [BackendKind.IROH] from config
 * truth alone.
 *
 * The other historical cases went away with the code they guarded: the shim
 * `WebSocketConnection` in g70jb.3, and the `/v1/health` shim probe in g70jb.4
 * ([ShimBackendDetector] no longer holds an HTTP client, so it cannot dial).
 */
class IrohBackendNeverDialsShimTest {

    @Test
    fun `iroh config classifies as IROH and uses the channel transport`() {
        for (url in IROH_URLS) {
            val config = config(url)
            assertEquals("expected IROH for $url", BackendKind.IROH, config.backendKind())
            assertTrue(config.backendKind().usesChannelTransport)
        }
    }

    @Test
    fun `detector classifies an iroh config as IROH`() {
        val detector = ShimBackendDetector(MutableStateFlow(config("iroh://node-abc")))

        assertEquals(BackendKind.IROH, detector.cachedActiveBackendKind())
        assertTrue(detector.cachedActiveUsesChannelTransport())
    }

    @Test
    fun `detector classifies a local runtime config as LOCAL_RUNTIME`() {
        val config = config("http://localhost:8291").copy(mode = LettaConfig.Mode.LOCAL)
        val detector = ShimBackendDetector(MutableStateFlow(config))

        assertEquals(BackendKind.LOCAL_RUNTIME, detector.cachedActiveBackendKind())
        assertFalse(detector.cachedActiveUsesChannelTransport())
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
