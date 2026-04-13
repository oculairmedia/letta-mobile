package com.letta.mobile.bot.runtime

import com.letta.mobile.bot.core.UsageInfo
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import kotlinx.coroutines.flow.Flow

interface LettaRuntimeClient {
    suspend fun createConversation(agentId: String, summary: String? = null): String

    fun streamConversationMessage(
        agentId: String,
        conversationId: String,
        input: String,
    ): Flow<LettaRuntimeEvent>

    suspend fun listMemoryBlocks(agentId: String): List<Block>

    suspend fun updateMemoryBlock(agentId: String, blockLabel: String, value: String): Block

    suspend fun listTools(tags: List<String>? = null): List<Tool>

    suspend fun attachTool(agentId: String, toolId: String)

    suspend fun upsertTool(params: ToolCreateParams): Tool

    suspend fun submitToolResult(
        conversationId: String,
        toolCallId: String,
        toolReturn: String,
    )
}

sealed interface LettaRuntimeEvent {
    val conversationId: String

    data class RawMessage(
        override val conversationId: String,
        val message: LettaMessage,
    ) : LettaRuntimeEvent

    data class AssistantDelta(
        override val conversationId: String,
        val messageId: String,
        val textDelta: String,
    ) : LettaRuntimeEvent

    data class ReasoningDelta(
        override val conversationId: String,
        val messageId: String,
        val textDelta: String,
    ) : LettaRuntimeEvent

    data class ToolCallRequested(
        override val conversationId: String,
        val messageId: String,
        val toolCalls: List<ToolCall>,
    ) : LettaRuntimeEvent

    data class Completed(
        override val conversationId: String,
        val finalText: String,
        val usage: UsageInfo? = null,
    ) : LettaRuntimeEvent
}
