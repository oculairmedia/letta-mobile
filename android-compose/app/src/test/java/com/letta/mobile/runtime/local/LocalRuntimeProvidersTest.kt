package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeProvidersTest {
    @Test
    fun `local runtime scheme trims delimiter-less urls`() {
        val provider = LocalKoogRuntimeProvider()

        assertTrue(
            provider.supports(
                LettaConfig(
                    id = "local-koog",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = " local-koog ",
                )
            )
        )
    }
}
