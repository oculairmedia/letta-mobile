package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
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

    @Test
    fun `local lettacode does not advertise unprojected tool or approval events`() {
        val provider = localLettaCodeProvider()

        val capabilities = provider.descriptor(
            LettaConfig(
                id = "local-lettacode",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
            )
        ).capabilities

        assertTrue(capabilities.supportsStreaming)
        assertTrue(capabilities.supportsMemFs)
        assertFalse(capabilities.supportsTools)
        assertFalse(capabilities.supportsApprovals)
    }

    @Test
    fun `local lettacode provider requires explicit scheme`() {
        val provider = localLettaCodeProvider()

        assertFalse(
            provider.supports(
                LettaConfig(
                    id = "local-default",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local://device",
                )
            )
        )
        assertTrue(
            provider.supports(
                LettaConfig(
                    id = "local-lettacode",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local-lettacode://device",
                )
            )
        )
    }

    private fun localLettaCodeProvider(): LocalLettaCodeRuntimeProvider = LocalLettaCodeRuntimeProvider(
        turnEngineFactory = LettaCodeTurnEngineFactory(
            client = object : LettaCodeHeadlessClient {
                override fun runTurn(command: TurnCommand, config: LettaConfig): Flow<RuntimeEventDraft> = emptyFlow()
            },
        ),
    )
}
