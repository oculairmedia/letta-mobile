package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.web.storage.LocalStorageSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-o4ygk.4.5: the web's backend survives a reload, the access token does not. */
class WebSettingsStoreTest {
    @Test
    fun theBackendAddressSurvivesAReloadButTheTokenDoesNot() {
        val settings = InMemorySecureSettingsStore()
        WebSettingsStore(settings).saveBackend(
            LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "wss://example.test/ws", accessToken = "secret"),
        )

        val config = WebSettingsStore(settings).load()

        assertEquals("wss://example.test/ws", config.serverUrl)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, config.mode)
        assertNull(config.accessToken)
    }

    @Test
    fun anUnknownStoredModeReadsAsTheDefault() {
        val config = WebSettingsStore(InMemorySecureSettingsStore(mapOf("backend.mode" to "NOT_A_MODE"))).load()

        assertEquals(LettaConfig.Mode.SELF_HOSTED, config.mode)
    }

    @Test
    fun localStorageNeverHoldsATokenKey() {
        val store = LocalStorageSettingsStore(prefix = "letta.web.test.")
        store.putString("letta.config.accessToken", "secret")

        assertEquals("secret", store.getString("letta.config.accessToken"))
        assertNull(kotlinx.browser.localStorage.getItem("letta.web.test.letta.config.accessToken"))
        store.clear()
    }
}
