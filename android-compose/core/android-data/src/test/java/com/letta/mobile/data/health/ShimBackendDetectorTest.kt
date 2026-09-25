package com.letta.mobile.data.health

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-g70jb.4: the detector is a pure config classifier now. A
 * leftover shim-era config (the admin shim's `:8291` address) is plain REST:
 * it must not report a channel transport, or the chat screen shows the WS idle
 * chip while the send actually goes over REST.
 */
class ShimBackendDetectorTest {

    @Test
    fun `leftover shim config classifies as REST without a channel transport`() {
        val detector = ShimBackendDetector(MutableStateFlow(config("http://localhost:8291")))

        assertEquals(BackendKind.REST, detector.cachedActiveBackendKind())
        assertFalse(detector.cachedActiveUsesChannelTransport())
        assertFalse(detector.activeUsesChannelTransport.value)
    }

    @Test
    fun `no active config classifies as REST`() {
        val detector = ShimBackendDetector(MutableStateFlow(null))

        assertEquals(BackendKind.REST, detector.cachedActiveBackendKind())
        assertFalse(detector.cachedActiveUsesChannelTransport())
    }

    @Test
    fun `classification follows the active config`() = runTest {
        val active = MutableStateFlow<LettaConfig?>(config("http://localhost:8291"))
        val detector = ShimBackendDetector(active)

        active.value = config("iroh://node-abc")

        // The cached accessor reads the config directly, so it is current at once.
        assertEquals(BackendKind.IROH, detector.cachedActiveBackendKind())
        // The shared flows are stateIn-ed on a background scope: await the update.
        assertEquals(BackendKind.IROH, detector.activeBackendKind.first { it == BackendKind.IROH })
        assertTrue(detector.activeUsesChannelTransport.first { it })
    }

    private fun config(url: String) = LettaConfig(
        id = "cfg",
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = url,
        accessToken = "token",
    )
}
