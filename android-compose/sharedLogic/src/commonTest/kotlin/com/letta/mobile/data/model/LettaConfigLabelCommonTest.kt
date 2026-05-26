package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LettaConfigLabelCommonTest {
    @Test
    fun labelsCloudLocalAndSelfHostedBackends() {
        assertEquals("Cloud", config(LettaConfig.Mode.CLOUD, "https://app.letta.com").toBackendLabel())
        assertEquals("Local LettaCode", config(LettaConfig.Mode.LOCAL, "local://device").toBackendLabel())
        assertEquals("Local Koog runtime", config(LettaConfig.Mode.LOCAL, "local-koog://device").toBackendLabel())
        assertEquals(
            "example.com",
            config(LettaConfig.Mode.SELF_HOSTED, "https://user:password@example.com/v1").toBackendLabel(),
        )
        assertEquals(
            "localhost:8283",
            config(LettaConfig.Mode.SELF_HOSTED, "http://localhost:8283/api").toBackendLabel(),
        )
    }

    private fun config(mode: LettaConfig.Mode, serverUrl: String): LettaConfig =
        LettaConfig(id = "test", mode = mode, serverUrl = serverUrl)
}
