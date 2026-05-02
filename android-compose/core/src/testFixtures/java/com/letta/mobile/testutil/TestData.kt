package com.letta.mobile.testutil

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.Tool
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive

object TestData {
    fun agent(
        id: String = "agent-1",
        name: String = "Test Agent",
        model: String? = "letta/letta-free",
        description: String? = "A test agent",
        tags: List<String> = listOf("test"),
        system: String? = null,
        blocks: List<Block> = emptyList(),
    ) = Agent(
        id = id,
        name = name,
        model = model,
        description = description,
        tags = tags,
        system = system,
        blocks = blocks,
    )

    fun conversation(
        id: String = "conv-1",
        agentId: String = "agent-1",
        summary: String? = "Test conversation",
    ) = Conversation(
        id = id,
        agentId = agentId,
        summary = summary,
    )

    fun appMessage(
        id: String = "msg-1",
        messageType: MessageType = MessageType.USER,
        content: String = "Hello",
        isPending: Boolean = false,
        localId: String? = null,
        toolName: String? = null,
        toolCallId: String? = null,
        date: Instant = Instant.parse("2024-03-15T10:00:00Z"),
    ) = AppMessage(
        id = id,
        date = date,
        messageType = messageType,
        content = content,
        isPending = isPending,
        localId = localId,
        toolName = toolName,
        toolCallId = toolCallId,
    )

    fun tool(
        id: String = "tool-1",
        name: String = "test_tool",
        description: String? = "A test tool",
    ) = Tool(
        id = id,
        name = name,
        description = description,
    )

    fun block(
        id: String = "block-1",
        label: String = "persona",
        value: String = "I am a helpful assistant.",
    ) = Block(
        id = id,
        label = label,
        value = value,
    )

    fun mcpServer(
        id: String = "mcp-1",
        serverName: String = "Test MCP",
        serverUrl: String? = "http://localhost:8080",
    ) = McpServer(
        id = id,
        serverName = serverName,
        serverUrl = serverUrl,
        type = "streamable_http",
    )

    fun mcpToolExecutionResult(
        status: String = "success",
        funcReturn: String = "ok",
        stdout: List<String>? = null,
        stderr: List<String>? = null,
    ) = McpToolExecutionResult(
        status = status,
        funcReturn = JsonPrimitive(funcReturn),
        stdout = stdout,
        stderr = stderr,
    )

    fun lettaConfig(
        id: String = "config-1",
        mode: LettaConfig.Mode = LettaConfig.Mode.CLOUD,
        serverUrl: String = "https://api.letta.com",
        accessToken: String? = "test-token",
    ) = LettaConfig(
        id = id,
        mode = mode,
        serverUrl = serverUrl,
        accessToken = accessToken,
    )
}
