package com.letta.mobile.data.health

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.repository.routedBackendKind
import org.junit.Assert.assertEquals
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
 * (classification is now the pure [routedBackendKind], which holds no client and cannot dial).
 */
class IrohBackendNeverDialsShimTest {

    @Test
    fun `iroh config classifies as IROH`() {
        for (url in IROH_URLS) {
            val config = config(url)
            assertEquals("expected IROH for $url", BackendKind.IROH, config.backendKind())
        }
    }

    @Test
    fun `routed kind classifies an iroh config as IROH`() {
        val kind = config("iroh://node-abc").routedBackendKind()

        assertEquals(BackendKind.IROH, kind)
    }

    @Test
    fun `routed kind classifies a local runtime config as LOCAL_RUNTIME`() {
        val kind = config("http://localhost:8291").copy(mode = LettaConfig.Mode.LOCAL).routedBackendKind()

        assertEquals(BackendKind.LOCAL_RUNTIME, kind)
    }

    @Test
    fun `routed kind classifies a ws address and a missing config as DIRECT_WS`() {
        for (config in listOf(config("ws://localhost:8291"), null)) {
            val kind = config.routedBackendKind()

            assertEquals("config=$config", BackendKind.DIRECT_WS, kind)
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
