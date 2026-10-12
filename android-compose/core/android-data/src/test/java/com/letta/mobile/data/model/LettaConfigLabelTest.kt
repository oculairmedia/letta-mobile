package com.letta.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class LettaConfigLabelTest {

    @Test
    fun `local label is local lettacode by default`() {
        assertEquals(
            "Local LettaCode",
            config(LettaConfig.Mode.LOCAL, "local://device").toBackendLabel(),
        )
    }

    @Test
    fun `local koog label is explicit`() {
        assertEquals(
            "Local Koog runtime",
            config(LettaConfig.Mode.LOCAL, "local-koog://device").toBackendLabel(),
        )
    }

    @Test
    fun `self hosted label removes credentials`() {
        assertEquals(
            "example.com",
            config(LettaConfig.Mode.SELF_HOSTED, "https://user:password@example.com/v1").toBackendLabel(),
        )
    }

    @Test
    fun `self hosted label preserves host port`() {
        assertEquals(
            "localhost:8283",
            config(LettaConfig.Mode.SELF_HOSTED, "http://localhost:8283/api").toBackendLabel(),
        )
    }

    @Test
    fun `self hosted fallback removes user info`() {
        assertEquals(
            "example.com",
            config(LettaConfig.Mode.SELF_HOSTED, "user:password@example.com/api").toBackendLabel(),
        )
    }

    private fun config(mode: LettaConfig.Mode, serverUrl: String): LettaConfig =
        LettaConfig(id = "test", mode = mode, serverUrl = serverUrl)
}
