package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeSettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohBackendHelperTest {
    @Test
    fun everyConfiguredBackendUsesTheChannel() {
        val irohSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket"
            )
        )
        assertTrue(irohSettings.activeBackendUsesChannel())

        // letta-mobile-by4vm.110.6: a ws:// App Server and the on-device runtime ride the channel too.
        val wsSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "ws", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://192.168.1.2:4500")
        )
        assertTrue(wsSettings.activeBackendUsesChannel())
        val localSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = "local-lettacode://device")
        )
        assertTrue(localSettings.activeBackendUsesChannel())

        val httpsSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "https", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://example.com"),
        )
        assertFalse("an http(s) address is the remote-source seam's", httpsSettings.activeBackendUsesChannel())

        val blankSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "blank", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "")
        )
        assertFalse(blankSettings.activeBackendUsesChannel())

        val nullSettings = FakeSettingsRepository(
            initialActiveConfig = null
        )
        assertFalse(nullSettings.activeBackendUsesChannel())
    }
}
