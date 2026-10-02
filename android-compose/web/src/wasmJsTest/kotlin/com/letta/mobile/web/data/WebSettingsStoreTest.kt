package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-o4ygk.4.5: the web's settings survive a reload, the access token does not. */
class WebSettingsStoreTest {
    @Test
    fun theBackendAddressSurvivesAReloadButTheTokenDoesNot() {
        val slot = MemorySlot()
        WebSettingsStore(slot).saveBackend(
            LettaConfig(id = "default", mode = LettaConfig.Mode.CLOUD, serverUrl = "wss://example.test/ws", accessToken = "secret"),
        )

        val config = WebSettingsStore(slot).load().config

        assertEquals("wss://example.test/ws", config.serverUrl)
        assertEquals(LettaConfig.Mode.CLOUD, config.mode)
        assertNull(config.accessToken)
        assertFalse(slot.document?.json.orEmpty().contains("secret"))
    }

    @Test
    fun conversationsOpenOnTheCanvasUntilTurnedOff() {
        val store = WebSettingsStore(MemorySlot())
        assertTrue(store.load().openChatsOnCanvas)

        store.save(store.load().copy(openChatsOnCanvas = false))

        assertFalse(store.load().openChatsOnCanvas)
    }

    @Test
    fun savingTheBackendKeepsTheOtherSettings() {
        val store = WebSettingsStore(MemorySlot())
        store.save(WebSavedSettings(openChatsOnCanvas = false))

        store.saveBackend(LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://h/ws", accessToken = null))

        assertEquals(WebSavedSettings(serverUrl = "ws://h/ws", openChatsOnCanvas = false), store.load())
    }

    @Test
    fun unreadableStoredSettingsReadAsTheDefaults() {
        val store = WebSettingsStore(MemorySlot(WebSettingsDocument("not json")))

        assertEquals(WebSavedSettings(), store.load())
    }

    private class MemorySlot(var document: WebSettingsDocument? = null) : WebSettingsSlot {
        override fun read(): WebSettingsDocument? = document

        override fun write(document: WebSettingsDocument?) {
            this.document = document
        }
    }
}
