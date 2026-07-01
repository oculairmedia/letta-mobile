package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExternalToolRegistryTest {
    @Test
    fun factoryDefaultRegistryAdvertisesNoTools() {
        val registry = ExternalToolRegistry.factoryDefault()
        val advertised = registry.listAdvertisedTools()

        assertTrue(advertised.isEmpty(), "Factory default registry should advertise no tools")
    }

    @Test
    fun registryOnlyAdvertisesToolsWithEnabledCapabilities() {
        val capabilities = RemoteCapabilities(
            imageHydration = true,
            goals = true,
            // Other capabilities disabled
        )

        val registry = ExternalToolRegistry.standard(capabilities)
        val advertised = registry.listAdvertisedTools()

        assertEquals(2, advertised.size, "Should advertise exactly 2 tools")
        
        val toolNames = advertised.map { it.name }.toSet()
        assertTrue("image_hydration" in toolNames, "Should include image_hydration tool")
        assertTrue("goals" in toolNames, "Should include goals tool")
    }

    @Test
    fun registryAdvertisesAllToolsWhenAllCapabilitiesEnabled() {
        val capabilities = RemoteCapabilities(
            imageHydration = true,
            subagentChips = true,
            goals = true,
            slashCommands = true,
            schedules = true,
            reflection = true,
            slimAgents = true,
            scopedPush = false, // Not relevant for tools
        )

        val registry = ExternalToolRegistry.standard(capabilities)
        val advertised = registry.listAdvertisedTools()

        assertEquals(7, advertised.size, "Should advertise all 7 extra tools")
        
        val toolNames = advertised.map { it.name }.toSet()
        assertTrue("image_hydration" in toolNames)
        assertTrue("subagent_chips" in toolNames)
        assertTrue("goals" in toolNames)
        assertTrue("slash_commands" in toolNames)
        assertTrue("schedules" in toolNames)
        assertTrue("reflection" in toolNames)
        assertTrue("slim_agents" in toolNames)
    }

    @Test
    fun invokeReturnsErrorForAdvertisedUnimplementedTool() = runTest {
        val capabilities = RemoteCapabilities(imageHydration = true)
        val registry = ExternalToolRegistry.standard(capabilities)

        val input = buildJsonObject {
            put("image_id", "test-image")
        }

        val result = registry.invoke("image_hydration", input)

        assertIs<ExternalToolResult.Error>(result, "Should return error for advertised but unimplemented tool")
    }

    @Test
    fun invokeReturnsErrorForUnadvertisedTool() = runTest {
        val capabilities = RemoteCapabilities(imageHydration = false)
        val registry = ExternalToolRegistry.standard(capabilities)

        val input = buildJsonObject {
            put("image_id", "test-image")
        }

        val result = registry.invoke("image_hydration", input)

        assertIs<ExternalToolResult.Error>(result, "Should return error for unadvertised tool")
        assertTrue(
            result.error.contains("not found or not advertised"),
            "Error should mention tool not advertised"
        )
    }

    @Test
    fun invokeReturnsErrorForNonexistentTool() = runTest {
        val capabilities = RemoteCapabilities(imageHydration = true)
        val registry = ExternalToolRegistry.standard(capabilities)

        val input = buildJsonObject { }

        val result = registry.invoke("nonexistent_tool", input)

        assertIs<ExternalToolResult.Error>(result, "Should return error for nonexistent tool")
    }

    @Test
    fun eachExtraToolMapsToCorrectCapability() {
        val tools = listOf(
            ImageHydrationTool() to Capability.ImageHydration,
            GoalsTool() to Capability.Goals,
            SchedulesTool() to Capability.Schedules,
            SlashCommandsTool() to Capability.SlashCommands,
            SubagentChipsTool() to Capability.SubagentChips,
            ReflectionTool() to Capability.Reflection,
            SlimAgentsTool() to Capability.SlimAgents,
        )

        tools.forEach { (tool, expectedCapability) ->
            assertEquals(
                expectedCapability,
                tool.capability,
                "Tool ${tool.name} should map to capability $expectedCapability"
            )
        }
    }

    @Test
    fun toolsHaveUniqueNames() {
        val tools = listOf(
            ImageHydrationTool(),
            GoalsTool(),
            SchedulesTool(),
            SlashCommandsTool(),
            SubagentChipsTool(),
            ReflectionTool(),
            SlimAgentsTool(),
        )

        val names = tools.map { it.name }
        val uniqueNames = names.toSet()

        assertEquals(
            names.size,
            uniqueNames.size,
            "All tools should have unique names"
        )
    }

    @Test
    fun toolsHaveDescriptions() {
        val tools = listOf(
            ImageHydrationTool(),
            GoalsTool(),
            SchedulesTool(),
            SlashCommandsTool(),
            SubagentChipsTool(),
            ReflectionTool(),
            SlimAgentsTool(),
        )

        tools.forEach { tool ->
            assertTrue(
                tool.description.isNotBlank(),
                "Tool ${tool.name} should have a non-blank description"
            )
        }
    }
}
