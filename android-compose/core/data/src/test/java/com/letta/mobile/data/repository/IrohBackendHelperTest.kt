package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.testutil.FakeSettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohBackendHelperTest {
    @Test
    fun testActiveBackendIsIroh() {
        val irohSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket"
            )
        )
        assertTrue(irohSettings.activeBackendIsIroh())

        val httpsSettings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "https",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "https://example.com"
            )
        )
        assertFalse(httpsSettings.activeBackendIsIroh())

        val nullSettings = FakeSettingsRepository(
            initialActiveConfig = null
        )
        assertFalse(nullSettings.activeBackendIsIroh())
    }
}
