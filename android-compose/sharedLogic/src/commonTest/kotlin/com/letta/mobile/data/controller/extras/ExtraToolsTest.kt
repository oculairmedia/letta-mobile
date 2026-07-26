package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ExtraToolsTest {
    @Test
    fun imageHydrationToolHasCorrectMetadata() {
        val tool = ImageHydrationTool()

        assertEquals("image_hydration", tool.name)
        assertEquals(Capability.ImageHydration, tool.capability)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    @Test
    fun imageHydrationToolInvokeReturnsSuccess() = runTest {
        val tool = ImageHydrationTool()
        val input = buildJsonObject {
            put("image_id", "test-image")
        }

        val result = tool.invoke(input)

        assertIs<ExternalToolResult.Success>(result)
    }

    @Test
    fun goalsToolHasCorrectMetadata() {
        val tool = GoalsTool()

        assertEquals("goals", tool.name)
        assertEquals(Capability.Goals, tool.capability)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    @Test
    fun goalsToolInvokeReturnsSuccess() = runTest {
        val tool = GoalsTool()
        val input = buildJsonObject {
            put("action", "list")
        }

        val result = tool.invoke(input)

        assertIs<ExternalToolResult.Success>(result)
    }

    @Test
    fun schedulesToolHasCorrectMetadata() {
        val tool = SchedulesTool()

        assertEquals("schedules", tool.name)
        assertEquals(Capability.Schedules, tool.capability)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    @Test
    fun schedulesToolInvokeReturnsSuccess() = runTest {
        val tool = SchedulesTool()
        val input = buildJsonObject {
            put("action", "list")
        }

        val result = tool.invoke(input)

        assertIs<ExternalToolResult.Success>(result)
    }

    @Test
    fun slashCommandsToolHasCorrectMetadata() {
        val tool = SlashCommandsTool()

        assertEquals("slash_commands", tool.name)
        assertEquals(Capability.SlashCommands, tool.capability)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    @Test
    fun slashCommandsToolInvokeReturnsSuccess() = runTest {
        val tool = SlashCommandsTool()
        val input = buildJsonObject {
            put("command", "/help")
        }

        val result = tool.invoke(input)

        assertIs<ExternalToolResult.Success>(result)
    }

    @Test
    fun subagentChipsToolHasCorrectMetadata() {
        val tool = SubagentChipsTool()

        assertEquals("subagent_chips", tool.name)
        assertEquals(Capability.SubagentChips, tool.capability)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    @Test
    fun subagentChipsToolInvokeReturnsSuccess() = runTest {
        val tool = SubagentChipsTool()
        val input = buildJsonObject {
            put("subagent_id", "test-subagent")
        }

        val result = tool.invoke(input)

        assertIs<ExternalToolResult.Success>(result)
    }

    @Test
    fun reflectionToolHasCorrectMetadata() {
        val tool = ReflectionTool()

        assertEquals("reflection", tool.name)
        assertEquals(Capability.Reflection, tool.capability)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    @Test
    fun reflectionToolInvokeReturnsSuccess() = runTest {
        val tool = ReflectionTool()
        val input = buildJsonObject {
            put("query", "What is my current context?")
        }

        val result = tool.invoke(input)

        assertIs<ExternalToolResult.Success>(result)
    }

    @Test
    fun slimAgentsToolHasCorrectMetadata() {
        val tool = SlimAgentsTool()

        assertEquals("slim_agents", tool.name)
        assertEquals(Capability.SlimAgents, tool.capability)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    @Test
    fun slimAgentsToolInvokeReturnsSuccess() = runTest {
        val tool = SlimAgentsTool()
        val input = buildJsonObject {
            put("agent_ids", buildJsonObject { })
            put("projection_type", "summary")
        }

        val result = tool.invoke(input)

        assertIs<ExternalToolResult.Success>(result)
    }
}
