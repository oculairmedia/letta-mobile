package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ExternalToolRegistrarTest {
    @Test
    fun noOpRegistrarDoesNotThrow() = runTest {
        val registrar = NoOpExternalToolRegistrar()

        val runtime = AppServerRuntimeScope(
            agentId = "agent-1",
            conversationId = "conv-1",
        )

        // Should not throw
        registrar.reRegisterAll(runtime)
    }
}
