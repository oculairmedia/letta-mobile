package com.letta.mobile.ui.shell

import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.shell.ShellDestination
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-1hzkq: which first screens count as usable. Expectations are written out, not derived. */
class FirstUsableContentTest {
    private val connected = BackendConnectionStatus.Connected("WebSocket")
    private val failed = BackendConnectionStatus.Failed("offline")

    @Test
    fun settingsIsUsableFromTheFirstFrameWhateverTheBackendIs() {
        for (connection in listOf(BackendConnectionStatus.Unconfigured, BackendConnectionStatus.Connecting, failed, connected)) {
            for (sourceReady in listOf(false, true)) {
                assertEquals(true, shellFirstScreenUsable(ShellDestination.Settings(), connection, sourceReady), "$connection $sourceReady")
            }
        }
    }

    @Test
    fun homeWaitsForTheBackendAndItsSource() {
        val home = ShellDestination.Home
        assertEquals(false, shellFirstScreenUsable(home, BackendConnectionStatus.Connecting, sourceReady = true))
        assertEquals(false, shellFirstScreenUsable(home, failed, sourceReady = true))
        assertEquals(false, shellFirstScreenUsable(home, connected, sourceReady = false))
        assertEquals(true, shellFirstScreenUsable(home, connected, sourceReady = true))
        assertEquals(true, shellFirstScreenUsable(home, BackendConnectionStatus.Unconfigured, sourceReady = true))
    }

    @Test
    fun aChatWaitsLikeHome() {
        val chat = ShellDestination.Chat("agent", "conversation")
        assertEquals(false, shellFirstScreenUsable(chat, BackendConnectionStatus.Connecting, sourceReady = true))
        assertEquals(true, shellFirstScreenUsable(chat, connected, sourceReady = true))
    }
}
