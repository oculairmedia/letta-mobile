package com.letta.mobile.domain

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageProcessor @Inject constructor(
    private val clientToolRegistry: ClientToolRegistry,
) {
    fun processStream(
        stream: Flow<LettaMessage>,
        agentId: String,
        conversationId: String?,
        messageApi: MessageApi,
    ): Flow<AppMessage> = flow {
        val messages = mutableListOf<AppMessage>()
        val contentAccumulator = mutableMapOf<String, StringBuilder>()
        val toolCallsById = mutableMapOf<String, String>()

        processStreamRecursive(
            stream = stream,
            agentId = agentId,
            conversationId = conversationId,
            messageApi = messageApi,
            messages = messages,
            contentAccumulator = contentAccumulator,
            toolCallsById = toolCallsById,
            depth = 0
        ) { message ->
            emit(message)
        }
    }

    private suspend fun processStreamRecursive(
        stream: Flow<LettaMessage>,
        agentId: String,
        conversationId: String?,
        messageApi: MessageApi,
        messages: MutableList<AppMessage>,
        contentAccumulator: MutableMap<String, StringBuilder>,
        toolCallsById: MutableMap<String, String>,
        depth: Int,
        onEmit: suspend (AppMessage) -> Unit,
    ) {
        if (depth >= MAX_DEPTH) {
            throw IllegalStateException("Maximum recursive tool chain depth ($MAX_DEPTH) exceeded")
        }

        try {
            withTimeout(CHAIN_TIMEOUT_MS) {
                stream.collect { lettaMessage ->
                    when (lettaMessage) {
                        is UserMessage -> {
                            val appMessage = AppMessage(
                                id = lettaMessage.id,
                                date = parseInstant(lettaMessage.date),
                                messageType = MessageType.USER,
                                content = lettaMessage.content
                            )
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is AssistantMessage -> {
                            val existingContent = contentAccumulator[lettaMessage.id]
                            if (existingContent != null) {
                                existingContent.append(lettaMessage.content)
                                val updatedMessage = AppMessage(
                                    id = lettaMessage.id,
                                    date = parseInstant(lettaMessage.date),
                                    messageType = MessageType.ASSISTANT,
                                    content = existingContent.toString()
                                )
                                val index = messages.indexOfFirst { it.id == lettaMessage.id }
                                if (index >= 0) {
                                    messages[index] = updatedMessage
                                } else {
                                    messages.add(updatedMessage)
                                }
                                onEmit(updatedMessage)
                            } else {
                                contentAccumulator[lettaMessage.id] = StringBuilder(lettaMessage.content)
                                val appMessage = AppMessage(
                                    id = lettaMessage.id,
                                    date = parseInstant(lettaMessage.date),
                                    messageType = MessageType.ASSISTANT,
                                    content = lettaMessage.content
                                )
                                messages.add(appMessage)
                                onEmit(appMessage)
                            }
                        }

                        is ReasoningMessage -> {
                            val appMessage = AppMessage(
                                id = lettaMessage.id,
                                date = parseInstant(lettaMessage.date),
                                messageType = MessageType.REASONING,
                                content = lettaMessage.reasoning
                            )
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is ToolCallMessage -> {
                            val firstToolCall = lettaMessage.effectiveToolCalls.firstOrNull()
                            val toolCallId = firstToolCall?.effectiveId
                            val toolName = firstToolCall?.name
                            if (!toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                                toolCallsById[toolCallId] = toolName
                            }
                            val appMessage = AppMessage(
                                id = lettaMessage.id,
                                date = parseInstant(lettaMessage.date),
                                messageType = MessageType.TOOL_CALL,
                                content = firstToolCall?.arguments.orEmpty(),
                                toolName = toolName,
                                toolCallId = toolCallId,
                            )
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is ToolReturnMessage -> {
                            val toolCallId = lettaMessage.toolReturn.toolCallId
                            val appMessage = AppMessage(
                                id = lettaMessage.id,
                                date = parseInstant(lettaMessage.date),
                                messageType = MessageType.TOOL_RETURN,
                                content = lettaMessage.toolReturn.funcResponse ?: "",
                                toolName = toolCallId?.let(toolCallsById::get),
                                toolCallId = toolCallId,
                            )
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is ApprovalRequestMessage -> {
                            lettaMessage.effectiveToolCalls.forEach { toolCall ->
                                val toolName = toolCall.name ?: return@forEach
                                val arguments = toolCall.arguments ?: return@forEach
                                if (clientToolRegistry.isClientTool(toolName)) {
                                    val result = clientToolRegistry.execute(toolName, arguments)
                                }
                            }
                        }

                        is ApprovalResponseMessage -> {
                            // Skip approval response messages for now
                        }

                        is EventMessage -> {
                            if (lettaMessage.eventType != "ping") {
                            }
                        }

                        is HiddenReasoningMessage -> {
                        }

                        is SystemMessage -> {
                        }

                        is PingMessage -> {
                        }

                        is UnknownMessage -> {
                            // Skip unknown message types
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Tool chain timed out after ${CHAIN_TIMEOUT_MS}ms", e)
        }
    }

    private fun parseInstant(dateString: String?): Instant {
        return if (dateString != null) {
            try {
                Instant.parse(dateString)
            } catch (e: Exception) {
                Instant.now()
            }
        } else {
            Instant.now()
        }
    }

    companion object {
        private const val MAX_DEPTH = 10
        private const val CHAIN_TIMEOUT_MS = 60_000L
    }
}
