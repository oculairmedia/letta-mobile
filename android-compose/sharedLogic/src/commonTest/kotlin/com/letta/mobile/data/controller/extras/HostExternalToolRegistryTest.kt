package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostExternalToolRegistryTest {
    @Test
    fun `host tool bypasses remote capability filtering without changing factory default`() = runTest {
        val tool = FakeHostTool()
        val registry = ExternalToolRegistry.hostTools(listOf(tool))

        assertEquals(listOf("device_action"), registry.listAdvertisedTools().map { it.name })
        assertEquals("executed", (registry.invoke("device_action", JsonObject(emptyMap())) as ExternalToolResult.Success).content)
        assertNull(ExternalToolRegistry.factoryDefault().advertisedToolsCommandGroups())
    }

    private class FakeHostTool : HostExternalTool {
        override val name = "device_action"
        override val description = "test"
        override val inputSchema: JsonObject? = null
        override val capability = Capability.ImageHydration
        override suspend fun invoke(input: JsonObject, agentId: String?) = ExternalToolResult.Success("executed")
    }
}
