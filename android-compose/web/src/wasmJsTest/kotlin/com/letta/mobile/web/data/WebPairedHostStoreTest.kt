package com.letta.mobile.web.data

import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-wisqe S1b: a paired host becomes the web backend at once and after a reload, with no token. */
class WebPairedHostStoreTest {
    private val nodeId = "ab".repeat(32)

    @Test
    fun aPairedHostIsTheBackendNowAndAfterAReloadWithoutAToken() = runTest {
        val settings = InMemorySecureSettingsStore()
        val config = mutableStateOf(
            LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "wss://old.test/ws", accessToken = "old-token"),
        )

        WebPairedHostStore(config, WebSettingsStore(settings)).saveAndActivate(PairedHost(nodeId, nodeId))

        assertEquals("iroh://$nodeId", config.value.serverUrl)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, config.value.mode)
        assertNull(config.value.accessToken, "the one-time invite is never kept as a credential")
        val reloaded = WebSettingsStore(settings).load()
        assertEquals("iroh://$nodeId", reloaded.serverUrl)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, reloaded.mode)
        assertNull(reloaded.accessToken)
    }
}
