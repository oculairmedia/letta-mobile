package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams

class FakeToolApi : ToolApi(null!!) {
    var tools = mutableListOf<Tool>()
    var agentTools = mutableMapOf<String, MutableList<Tool>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listTools(): List<Tool> {
        calls.add("listTools")
        if (shouldFail) throw ApiException(500, "Server error")
        return tools.toList()
    }

    override suspend fun attachTool(agentId: String, toolId: String) {
        calls.add("attachTool:$agentId:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        val tool = tools.find { it.id == toolId } ?: throw ApiException(404, "Tool not found")
        agentTools.getOrPut(agentId) { mutableListOf() }.add(tool)
    }

    override suspend fun detachTool(agentId: String, toolId: String) {
        calls.add("detachTool:$agentId:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        agentTools[agentId]?.removeAll { it.id == toolId }
    }

    override suspend fun getTool(toolId: String): Tool {
        calls.add("getTool:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        return tools.find { it.id == toolId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        calls.add("upsertTool:${params.name}")
        if (shouldFail) throw ApiException(500, "Server error")
        val tool = TestData.tool(id = "new-${tools.size}", name = params.name ?: "unnamed")
        tools.add(tool)
        return tool
    }
}
