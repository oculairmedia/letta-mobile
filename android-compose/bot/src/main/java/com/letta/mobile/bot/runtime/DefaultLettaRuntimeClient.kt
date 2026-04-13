package com.letta.mobile.bot.runtime

import com.letta.mobile.bot.core.UsageInfo
import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalResult
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.stream.SseParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@Singleton
class DefaultLettaRuntimeClient @Inject constructor(
    private val messageApi: MessageApi,
    private val conversationApi: ConversationApi,
    private val blockApi: BlockApi,
    private val toolApi: ToolApi,
) : LettaRuntimeClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createConversation(agentId: String, summary: String?): String {
        return conversationApi.createConversation(
            ConversationCreateParams(agentId = agentId, summary = summary)
        ).id
    }

    override fun streamConversationMessage(
        agentId: String,
        conversationId: String,
        input: String,
    ): Flow<LettaRuntimeEvent> = flow {
        val request = MessageCreateRequest(
            input = input,
            streaming = true,
        )

        val streamChannel = messageApi.sendConversationMessage(conversationId, request)
        val assistantContentById = linkedMapOf<String, StringBuilder>()

        SseParser.parse(streamChannel).collect { lettaMessage ->
            emit(LettaRuntimeEvent.RawMessage(conversationId = conversationId, message = lettaMessage))

            when (lettaMessage) {
                is AssistantMessage -> {
                    val buffer = assistantContentById.getOrPut(lettaMessage.id) { StringBuilder() }
                    buffer.append(lettaMessage.content)
                    emit(
                        LettaRuntimeEvent.AssistantDelta(
                            conversationId = conversationId,
                            messageId = lettaMessage.id,
                            textDelta = lettaMessage.content,
                        )
                    )
                }

                is ReasoningMessage -> {
                    emit(
                        LettaRuntimeEvent.ReasoningDelta(
                            conversationId = conversationId,
                            messageId = lettaMessage.id,
                            textDelta = lettaMessage.reasoning,
                        )
                    )
                }

                is ToolCallMessage -> {
                    val toolCalls = lettaMessage.effectiveToolCalls.filter { it.name != null }
                    if (toolCalls.isNotEmpty()) {
                        emit(
                            LettaRuntimeEvent.ToolCallRequested(
                                conversationId = conversationId,
                                messageId = lettaMessage.id,
                                toolCalls = toolCalls,
                            )
                        )
                    }
                }

                else -> Unit
            }
        }

        emit(
            LettaRuntimeEvent.Completed(
                conversationId = conversationId,
                finalText = assistantContentById.values.joinToString(separator = "\n\n") { it.toString() },
                usage = null,
            )
        )
    }

    override suspend fun listMemoryBlocks(agentId: String): List<Block> = blockApi.listBlocks(agentId)

    override suspend fun updateMemoryBlock(agentId: String, blockLabel: String, value: String): Block {
        return blockApi.updateAgentBlock(
            agentId = agentId,
            blockLabel = blockLabel,
            params = BlockUpdateParams(value = value),
        )
    }

    override suspend fun listTools(tags: List<String>?): List<Tool> = toolApi.listTools(tags = tags, limit = 1000)

    override suspend fun attachTool(agentId: String, toolId: String) {
        toolApi.attachTool(agentId, toolId)
    }

    override suspend fun upsertTool(params: ToolCreateParams): Tool = toolApi.upsertTool(params)

    override suspend fun submitToolResult(
        conversationId: String,
        toolCallId: String,
        toolReturn: String,
    ) {
        val request = MessageCreateRequest(
            messages = listOf(
                json.encodeToJsonElement(
                    ApprovalCreate.serializer(),
                    ApprovalCreate(
                        approvals = listOf(
                            ApprovalResult(
                                toolCallId = toolCallId,
                                approve = true,
                                status = "success",
                                toolReturn = toolReturn,
                            )
                        ),
                        approve = true,
                    )
                )
            ),
            streaming = false,
        )

        messageApi.sendConversationMessage(conversationId, request)
    }
}
