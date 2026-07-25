package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtraToolsTest {

    @Test
    fun p06EveryToolSchemaDeclaresRequiredAndEnumAsJsonArrays() {
        val tools = listOf(
            ImageHydrationTool(), GoalsTool(), SchedulesTool(), SlashCommandsTool(),
            SubagentChipsTool(), ReflectionTool(), SlimAgentsTool(),
        )
        for (tool in tools) {
            val schema = assertNotNull(tool.inputSchema, "${tool.name}: inputSchema")
            val required = schema["required"]
            assertIs<JsonArray>(required, "${tool.name}: `required` must be a JSON array, not an object")
            // Any `enum` inside a property must also be an array.
            val props = schema["properties"] as? JsonObject
            props?.values?.forEach { prop ->
                (prop as? JsonObject)?.get("enum")?.let {
                    assertIs<JsonArray>(it, "${tool.name}: property `enum` must be a JSON array")
                }
            }
        }
    }

    @Test
    fun p06RequiredArraysNameTheMandatoryFields() {
        fun req(tool: ExternalTool) =
            (assertNotNull(tool.inputSchema)["required"] as JsonArray).map { it.toString().trim('"') }
        assertTrue(req(GoalsTool()).contains("action"))
        assertTrue(req(SchedulesTool()).contains("action"))
        assertTrue(req(SlashCommandsTool()).contains("command"))
        assertTrue(req(SubagentChipsTool()).contains("subagent_id"))
        assertTrue(req(ReflectionTool()).contains("query"))
        assertTrue(req(SlimAgentsTool()).contains("agent_ids"))
    }

    /**
     * One row per advertised-but-stub extra tool. Table-driven so the metadata and
     * invoke-error assertions stay identical across every tool (was 7 near-identical
     * `xHasCorrectMetadata` + 7 `xInvokeReturnsUnimplementedError` cases).
     */
    private data class ToolCase(
        val label: String,
        val factory: () -> ExternalTool,
        val expectedName: String,
        val expectedCapability: Capability,
        val sampleInput: JsonObject,
    )

    private val cases = listOf(
        ToolCase(
            label = "image_hydration",
            factory = { ImageHydrationTool() },
            expectedName = "image_hydration",
            expectedCapability = Capability.ImageHydration,
            sampleInput = buildJsonObject { put("image_id", "test-image") },
        ),
        ToolCase(
            label = "goals",
            factory = { GoalsTool() },
            expectedName = "goals",
            expectedCapability = Capability.Goals,
            sampleInput = buildJsonObject { put("action", "list") },
        ),
        ToolCase(
            label = "schedules",
            factory = { SchedulesTool() },
            expectedName = "schedules",
            expectedCapability = Capability.Schedules,
            sampleInput = buildJsonObject { put("action", "list") },
        ),
        ToolCase(
            label = "slash_commands",
            factory = { SlashCommandsTool() },
            expectedName = "slash_commands",
            expectedCapability = Capability.SlashCommands,
            sampleInput = buildJsonObject { put("command", "/help") },
        ),
        ToolCase(
            label = "subagent_chips",
            factory = { SubagentChipsTool() },
            expectedName = "subagent_chips",
            expectedCapability = Capability.SubagentChips,
            sampleInput = buildJsonObject { put("subagent_id", "test-subagent") },
        ),
        ToolCase(
            label = "reflection",
            factory = { ReflectionTool() },
            expectedName = "reflection",
            expectedCapability = Capability.Reflection,
            sampleInput = buildJsonObject { put("query", "What is my current context?") },
        ),
        ToolCase(
            label = "slim_agents",
            factory = { SlimAgentsTool() },
            expectedName = "slim_agents",
            expectedCapability = Capability.SlimAgents,
            sampleInput = buildJsonObject {
                put("agent_ids", buildJsonObject { })
                put("projection_type", "summary")
            },
        ),
    )

    @Test
    fun everyToolReportsCorrectMetadata() {
        for (case in cases) {
            val tool = case.factory()
            assertEquals(case.expectedName, tool.name, "name for ${case.label}")
            assertEquals(case.expectedCapability, tool.capability, "capability for ${case.label}")
            assertNotNull(tool.description, "description for ${case.label}")
            assertNotNull(tool.inputSchema, "inputSchema for ${case.label}")
        }
    }

    @Test
    fun everyToolInvokeReturnsUnimplementedError() = runTest {
        for (case in cases) {
            val result = case.factory().invoke(case.sampleInput)

            // Advertised-but-stub tools must report a structured error, not a fake success.
            assertIs<ExternalToolResult.Error>(result, "result type for ${case.label}")
            assertTrue(
                result.error.contains("not yet implemented"),
                "error message for ${case.label}",
            )
        }
    }
}
