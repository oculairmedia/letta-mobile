package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolSchemaGenerateParams
import com.letta.mobile.data.model.ToolUpdateParams
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
        val tool = tools.find { it.id.value == toolId } ?: throw ApiException(404, "Tool not found")
        agentTools.getOrPut(agentId) { mutableListOf() }.add(tool)
    }

    override suspend fun detachTool(agentId: String, toolId: String) {
        calls.add("detachTool:$agentId:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        agentTools[agentId]?.removeAll { it.id.value == toolId }
    }

    override suspend fun getTool(toolId: String): Tool {
        calls.add("getTool:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        return tools.find { it.id.value == toolId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun countTools(): Int {
        calls.add("countTools")
        if (shouldFail) throw ApiException(500, "Server error")
        return tools.size
    }

    override suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool {
        calls.add("updateTool:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = tools.indexOfFirst { it.id.value == toolId }
        if (index == -1) throw ApiException(404, "Not found")
        val current = tools[index]
        val updatedName = params.jsonSchema?.schemaName() ?: current.name
        val updated = current.copy(
            name = updatedName,
            description = params.description ?: current.description,
            sourceCode = params.sourceCode ?: current.sourceCode,
            sourceType = params.sourceType ?: current.sourceType,
            jsonSchema = params.jsonSchema ?: current.jsonSchema,
            tags = params.tags ?: current.tags,
        )
        tools[index] = updated
        return updated
    }

    override suspend fun deleteTool(toolId: String) {
        calls.add("deleteTool:$toolId")
        if (shouldFail) throw ApiException(500, "Server error")
        if (tools.none { it.id.value == toolId }) throw ApiException(404, "Not found")
        tools.removeAll { it.id.value == toolId }
        agentTools.replaceAll { _, toolList -> toolList.filterNot { it.id.value == toolId }.toMutableList() }
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool {
        val toolName = params.jsonSchema?.schemaName() ?: deriveToolName(params.sourceCode)
        calls.add("upsertTool:$toolName")
        if (shouldFail) throw ApiException(500, "Server error")
        val existingIndex = tools.indexOfFirst { it.name == toolName }
        val tool = Tool(
            id = if (existingIndex >= 0) tools[existingIndex].id else ToolId("new-${tools.size}"),
            name = toolName,
            description = params.description,
            sourceCode = params.sourceCode,
            tags = params.tags ?: emptyList(),
            toolType = if (existingIndex >= 0) tools[existingIndex].toolType else "custom",
            sourceType = params.sourceType.ifBlank { if (existingIndex >= 0) tools[existingIndex].sourceType ?: "python" else "python" },
            jsonSchema = params.jsonSchema ?: buildJsonObject { put("name", toolName) },
        )
        if (existingIndex >= 0) {
            tools[existingIndex] = tool
        } else {
            tools.add(tool)
        }
        return tool
    }

    override suspend fun generateJsonSchema(params: ToolSchemaGenerateParams): JsonObject {
        calls.add("generateJsonSchema:${params.sourceType}")
        if (shouldFail) throw ApiException(500, "Server error")
        val toolName = deriveToolName(params.code)
        return buildJsonObject {
            put("name", toolName)
            put("description", "Generated schema for $toolName")
        }
    }

    private fun JsonObject.schemaName(): String? = this["name"]?.jsonPrimitive?.content

    private fun deriveToolName(sourceCode: String): String {
        val pythonMatch = Regex("""def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""").find(sourceCode)
        if (pythonMatch != null) {
            return pythonMatch.groupValues[1]
        }

        val typescriptMatch = Regex("""function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""").find(sourceCode)
        if (typescriptMatch != null) {
            return typescriptMatch.groupValues[1]
        }

        return "generated_tool"
    }
}
