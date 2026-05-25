package com.letta.mobile.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainIdsTest {
    @Serializable
    private data class Fixture(
        val agentId: AgentId,
        val projectId: ProjectId,
        val toolId: ToolId,
        val blockId: BlockId,
    )

    @Test
    fun serializesInlineIdsAsStrings() {
        val fixture = Fixture(
            agentId = AgentId("agent-1"),
            projectId = ProjectId("project-1"),
            toolId = ToolId("tool-1"),
            blockId = BlockId("block-1"),
        )

        assertEquals(
            """{"agentId":"agent-1","projectId":"project-1","toolId":"tool-1","blockId":"block-1"}""",
            Json.encodeToString(fixture),
        )
    }
}
