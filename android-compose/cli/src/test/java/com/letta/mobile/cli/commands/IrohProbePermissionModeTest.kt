package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IrohProbePermissionModeTest {
    @Test
    fun `probe options preserve requested strict mode`() {
        val options = IrohProbeOptions(
            token = null,
            adminBaseUrl = "http://127.0.0.1:9",
            agentId = "agent-test",
            message = "probe",
            seedMessages = 1,
            payloadBytes = 1,
            hydrateBudgetMs = 1,
            secondTurnDelayMs = 0,
            idleMs = 0,
            timeoutMs = 1,
            strictRedialDedupe = false,
            wrapperRestartCmd = null,
            dumpFramesPath = null,
            permissionMode = AppServerPermissionMode.Strict,
        )

        assertEquals(AppServerPermissionMode.Strict, options.permissionMode)
    }

    @Test
    fun `all production wire permission modes parse for probe use`() {
        val expected = mapOf(
            "standard" to AppServerPermissionMode.Standard,
            "acceptEdits" to AppServerPermissionMode.AcceptEdits,
            "strict" to AppServerPermissionMode.Strict,
            "unrestricted" to AppServerPermissionMode.Unrestricted,
        )

        assertEquals(expected, expected.keys.associateWith(AppServerPermissionMode::fromWireValue))
    }
}
