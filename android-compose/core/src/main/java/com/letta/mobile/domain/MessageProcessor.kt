package com.letta.mobile.domain

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.mapper.MessageMappingState
import com.letta.mobile.data.mapper.toAppMessage
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
        val mappingState = MessageMappingState()

        processStreamRecursive(
            stream = stream,
            agentId = agentId,
            conversationId = conversationId,
            messageApi = messageApi,
            messages = messages,
            contentAccumulator = contentAccumulator,
            mappingState = mappingState,
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
        mappingState: MessageMappingState,
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
                            val appMessage = lettaMessage.toAppMessage(mappingState) ?: return@collect
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is AssistantMessage -> {
                            val appMessage = lettaMessage.toAppMessage(mappingState) ?: return@collect
                            val existingContent = contentAccumulator[lettaMessage.id]
                            if (appMessage.generatedUi != null) {
                                contentAccumulator.remove(lettaMessage.id)
                                val index = messages.indexOfFirst { it.id == lettaMessage.id }
                                if (index >= 0) {
                                    messages[index] = appMessage
                                } else {
                                    messages.add(appMessage)
                                }
                                onEmit(appMessage)
                            } else if (existingContent != null) {
                                existingContent.append(lettaMessage.content)
                                val updatedMessage = appMessage.copy(content = existingContent.toString())
                                val index = messages.indexOfFirst { it.id == lettaMessage.id }
                                if (index >= 0) {
                                    messages[index] = updatedMessage
                                } else {
                                    messages.add(updatedMessage)
                                }
                                onEmit(updatedMessage)
                            } else {
                                contentAccumulator[lettaMessage.id] = StringBuilder(lettaMessage.content)
                                messages.add(appMessage)
                                onEmit(appMessage)
                            }
                        }

                        is ReasoningMessage -> {
                            val appMessage = lettaMessage.toAppMessage(mappingState) ?: return@collect
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is ToolCallMessage -> {
                            val appMessage = lettaMessage.toAppMessage(mappingState) ?: return@collect
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is ToolReturnMessage -> {
                            val appMessage = lettaMessage.toAppMessage(mappingState) ?: return@collect
                            messages.add(appMessage)
                            onEmit(appMessage)
                        }

                        is ApprovalRequestMessage -> {
                            val appMessage = lettaMessage.toAppMessage(mappingState) ?: return@collect
                            messages.add(appMessage)
                            onEmit(appMessage)

                            // Execute client tools if applicable
                            lettaMessage.effectiveToolCalls.forEach { toolCall ->
                                val name = toolCall.name ?: return@forEach
                                val arguments = toolCall.arguments ?: return@forEach
                                if (clientToolRegistry.isClientTool(name)) {
                                    clientToolRegistry.execute(name, arguments)
                                }
                            }
                        }

                        is ApprovalResponseMessage -> {
                            val appMessage = lettaMessage.toAppMessage(mappingState) ?: return@collect
                            messages.add(appMessage)
                            onEmit(appMessage)
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

    companion object {
        private const val MAX_DEPTH = 10
        private const val CHAIN_TIMEOUT_MS = 60_000L
    }
}
