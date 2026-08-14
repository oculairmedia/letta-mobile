package com.letta.mobile.data.controller.extras

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentDiscoveryToolTest {
    private val agents = listOf(
        DiscoverableAgent("agent-c356", "PM-letta-mobile", aliases = listOf("pm"), role = "pm", capabilities = listOf("messaging"), host = "home"),
        DiscoverableAgent("agent-c999", "PM-letta-mobile", aliases = listOf("backup"), role = "pm", capabilities = listOf("messaging"), host = "backup"),
        DiscoverableAgent("agent-x", "Meridian", capabilities = listOf("messaging", "admin"), host = "home"),
    )

    @Test
    fun exactAndLegacyIdsNormalize() = runTest {
        val tool = AgentDiscoveryTool { agents }
        val result = tool.invoke(buildJsonObject { put("query", "letta_agent-c356") }, null)
        val body = Json.parseToJsonElement((result as ExternalToolResult.Success).content).jsonObject
        assertEquals("agent-c356", body["agents"]!!.jsonArray.single().jsonObject["agentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun duplicateExactNamesReturnAmbiguousCandidates() = runTest {
        val tool = AgentDiscoveryTool { agents }
        val body = Json.parseToJsonElement((tool.invoke(buildJsonObject { put("query", "PM-letta-mobile") }, null) as ExternalToolResult.Success).content).jsonObject
        assertTrue(body["ambiguous"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, body["agents"]!!.jsonArray.size)
    }

    @Test
    fun filtersAndPaginationAreBounded() = runTest {
        val tool = AgentDiscoveryTool { agents }
        val body = Json.parseToJsonElement((tool.invoke(buildJsonObject { put("capability", "admin"); put("limit", 1) }, null) as ExternalToolResult.Success).content).jsonObject
        assertEquals(1, body["agents"]!!.jsonArray.size)
        assertEquals("agent-x", body["agents"]!!.jsonArray.single().jsonObject["agentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingAgentIsTypedNotFound() = runTest {
        val result = AgentDiscoveryTool { agents }.invoke(buildJsonObject { put("query", "missing") }, null)
        assertEquals("agent_not_found", (result as ExternalToolResult.Error).error)
    }
}
