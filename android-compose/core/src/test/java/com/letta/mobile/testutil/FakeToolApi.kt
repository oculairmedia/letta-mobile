package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import io.mockk.mockk

class FakeToolApi : ToolApi(mockk(relaxed = true)) {
    var tools = mutableListOf<Tool>()
    var agentTools = mutableMapOf<String, MutableList<Tool>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listTools(
        tags: List<String>?,
        limit: Int?,
        offset: Int?
    ): List<Tool> {
        calls.add("listTools")
        if (shouldFail) throw ApiException(500, "Server error")
        var result = tools.toList()
        if (offset != null) result = result.drop(offset)
        if (limit != null) result = result.take(limit)
        return result
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

    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool {
        calls.add("updateTool:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = tools.indexOfFirst { it.id == toolId }
        if (index == -1) throw ApiException(404, "Not found")
        val current = tools[index]
        val updated = current.copy(
            name = params.name ?: current.name,
            description = params.description ?: current.description,
            sourceCode = params.sourceCode ?: current.sourceCode,
            tags = params.tags ?: current.tags,
        )
        tools[index] = updated
        return updated
    }

    override suspend fun deleteTool(toolId: String) {
        calls.add("deleteTool:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        if (tools.none { it.id == toolId }) throw ApiException(404, "Not found")
        tools.removeAll { it.id == toolId }
        agentTools.replaceAll { _, toolList -> toolList.filterNot { it.id == toolId }.toMutableList() }
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        calls.add("upsertTool:${params.name}")
        if (shouldFail) throw ApiException(500, "Server error")
        val existingIndex = tools.indexOfFirst { it.name == params.name }
        val tool = Tool(
            id = if (existingIndex >= 0) tools[existingIndex].id else "new-${tools.size}",
            name = params.name,
            description = params.description,
            sourceCode = params.sourceCode,
            tags = params.tags ?: emptyList(),
            toolType = if (existingIndex >= 0) tools[existingIndex].toolType else "custom",
            sourceType = if (existingIndex >= 0) tools[existingIndex].sourceType else "python",
        )
        if (existingIndex >= 0) {
            tools[existingIndex] = tool
        } else {
            tools.add(tool)
        }
        return tool
    }
}
