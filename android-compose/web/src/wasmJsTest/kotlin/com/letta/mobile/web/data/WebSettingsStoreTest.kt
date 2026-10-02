package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-o4ygk.4.5: the web's settings survive a reload, the access token does not. */
class WebSettingsStoreTest {
    @Test
    fun theBackendAddressSurvivesAReloadButTheTokenDoesNot() = runTest {
        val slot = MemorySlot()
        val settings = WebSettings(WebSettingsPreferencesStore(slot))
        settings.saveConfig(
            LettaConfig(id = "default", mode = LettaConfig.Mode.CLOUD, serverUrl = "wss://example.test/ws", accessToken = "secret"),
        )

        val reloaded = WebSettingsPreferencesStore(slot)
        val config = WebSettings(reloaded).config(reloaded.snapshots.value)

        assertEquals("wss://example.test/ws", config.serverUrl)
        assertEquals(LettaConfig.Mode.CLOUD, config.mode)
        assertNull(config.accessToken)
        assertFalse(slot.value.orEmpty().contains("secret"))
    }

    @Test
    fun conversationsOpenOnTheCanvasUntilTurnedOff() = runTest {
        val store = WebSettingsPreferencesStore(MemorySlot())
        val settings = WebSettings(store)
        assertTrue(settings.openChatsOnCanvas(store.snapshots.value))

        store.edit { it.putBoolean("chat.open_on_canvas", false) }

        assertFalse(settings.openChatsOnCanvas(store.snapshots.value))
    }

    @Test
    fun everyValueKindRoundTripsAndClearAllEmptiesTheSlot() = runTest {
        val slot = MemorySlot()
        val store = WebSettingsPreferencesStore(slot)
        store.edit { editor ->
            editor.putString("s", "text")
            editor.putFloat("f", 1.5f)
            editor.putStringSet("set", setOf("a", "b"))
        }

        val reloaded = WebSettingsPreferencesStore(slot).snapshots.value
        assertEquals("text", reloaded.getString("s"))
        assertEquals(1.5f, reloaded.getFloat("f"))
        assertEquals(setOf("a", "b"), reloaded.getStringSet("set"))

        store.clearAll()
        assertNull(slot.value)
    }

    @Test
    fun unreadableStoredSettingsReadAsEmpty() {
        val store = WebSettingsPreferencesStore(MemorySlot("not json"))

        assertNull(store.snapshots.value.getString("backend.server_url"))
    }

    private class MemorySlot(var value: String? = null) : WebSettingsSlot {
        override fun read(): String? = value

        override fun write(value: String?) {
            this.value = value
        }
    }
}
