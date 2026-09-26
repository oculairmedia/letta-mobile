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

    @Test
    fun labelsIrohAddressesByDialHostNotByTheWholeTicket() {
        val nodeId = "330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f"
        assertEquals(
            "Iroh \u00b7 192.168.50.90:4501",
            config(LettaConfig.Mode.SELF_HOSTED, "iroh://$nodeId@192.168.50.90:4501,100.93.254.12:4501").toBackendLabel(),
        )
        assertEquals(
            "Iroh \u00b7 330415cc\u2026",
            config(LettaConfig.Mode.SELF_HOSTED, "iroh://$nodeId").toBackendLabel(),
        )
        assertEquals(
            "Iroh \u00b7 endpoint\u2026",
            config(LettaConfig.Mode.SELF_HOSTED, "iroh://endpointaazqifomcxarcwlnbmmlomcedptxc64sq").toBackendLabel(),
        )
    }

    private fun config(mode: LettaConfig.Mode, serverUrl: String): LettaConfig =
        LettaConfig(id = "test", mode = mode, serverUrl = serverUrl)
}
