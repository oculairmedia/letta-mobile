package com.letta.mobile.web

import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.web.data.WebPairedHostStore
import com.letta.mobile.web.data.WebSettingsStore
import kotlin.test.Test
import kotlin.test.assertNull

/** letta-mobile-wisqe: the browser has no QR encoder, so it says so instead of throwing after the host minted a code. */
class WebPairingPortsTest {
    @Test
    fun theBrowserReportsNoQrInsteadOfThrowing() {
        val config = LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "", accessToken = null)
        val hostStore = WebPairedHostStore(mutableStateOf(config), WebSettingsStore(InMemorySecureSettingsStore()))
        val ports = WebPairingPorts(WebBackend(config), ShellWorkspaceState(), hostStore, adminRpc = null)

        assertNull(ports.qrEncoder.encode("meridian://pair?qr=letta-qr-v1.x"))
    }
}
