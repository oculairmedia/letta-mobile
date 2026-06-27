package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.reconnect.ExternalToolRegistrar
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ExternalToolRegistrarTest {
    @Test
    fun registryImplementsExternalToolRegistrarInterface() {
        val registry = ExternalToolRegistry.factoryDefault()

        assertTrue(
            registry is ExternalToolRegistrar,
            "ExternalToolRegistry should implement ExternalToolRegistrar interface"
        )
    }

    @Test
    fun reRegisterAllDoesNotThrow() = runTest {
        val capabilities = RemoteCapabilities(imageHydration = true, goals = true)
        val registry = ExternalToolRegistry.standard(capabilities)

        val runtime = AppServerRuntimeScope(
            agentId = "test-agent",
            conversationId = "test-conversation",
            actingUserId = "test-user"
        )

        // Should not throw
        registry.reRegisterAll(runtime)
    }

    @Test
    fun factoryDefaultRegistryReRegisterAllDoesNotThrow() = runTest {
        val registry = ExternalToolRegistry.factoryDefault()

        val runtime = AppServerRuntimeScope(
            agentId = "test-agent",
            conversationId = "test-conversation",
            actingUserId = "test-user"
        )

        // Should not throw
        registry.reRegisterAll(runtime)
    }
}
