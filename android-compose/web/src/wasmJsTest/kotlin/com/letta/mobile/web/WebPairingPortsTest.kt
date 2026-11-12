package com.letta.mobile.web

import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.web.data.WebPairedHostStore
import com.letta.mobile.web.data.WebSettingsStore
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The browser draws a freshly minted invite as a QR grid with the portable encoder. */
class WebPairingPortsTest {
    @Test
    fun theBrowserDrawsTheInviteAsAQrGrid() {
        val config = LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "", accessToken = null)
        val hostStore = WebPairedHostStore(mutableStateOf(config), WebSettingsStore(InMemorySecureSettingsStore()))
        val ports = WebPairingPorts(WebBackend(config), ShellWorkspaceState(), hostStore, adminRpc = null)

        val grid = assertNotNull(ports.qrEncoder.encode("meridian://pair?qr=letta-qr-v1.x"))
        assertTrue(grid.size >= 21, "a QR grid is at least version 1 (21x21), was ${grid.size}")
    }
}
