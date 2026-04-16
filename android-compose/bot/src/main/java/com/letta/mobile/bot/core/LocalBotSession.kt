package com.letta.mobile.bot.core

import android.util.Log
import com.letta.mobile.bot.channel.ChannelDelivery
import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.channel.DeliveryResult
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.context.DeviceContextProvider
import com.letta.mobile.bot.message.DirectiveParser
import com.letta.mobile.bot.message.MessageEnvelopeFormatter
import com.letta.mobile.bot.message.MessageQueue
import com.letta.mobile.bot.runtime.LettaRuntimeClient
import com.letta.mobile.bot.runtime.LettaRuntimeEvent
import com.letta.mobile.bot.skills.BotSkillRegistry
import com.letta.mobile.bot.tools.BotToolExecutionResult
import com.letta.mobile.bot.tools.BotToolRegistry
import com.letta.mobile.bot.tools.BotToolSync
import com.letta.mobile.data.model.AssistantMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local bot session — runs entirely on-device using the Letta SDK API.
 * Kotlin equivalent of lettabot's LettaBot class (AgentSession implementation).
 *
 * This is the core of the on-device bot. It:
 * 1. Receives messages from channel adapters via the gateway
 * 2. Enriches them with device context via [MessageEnvelopeFormatter]
 * 3. Routes to the correct conversation based on [ConversationMode]
 * 4. Sends to the Letta agent via [MessageApi]
 * 5. Parses directives from the response via [DirectiveParser]
 * 6. Returns the clean response + directives for channel delivery
 *
 * Unlike [RemoteBotSession], this has full access to Android APIs through
 * [DeviceContextProvider] implementations, enabling rich device-aware interactions.
 */
class LocalBotSession @AssistedInject constructor(
    @Assisted private val config: BotConfig,
    private val runtimeClient: LettaRuntimeClient,
    private val envelopeFormatter: MessageEnvelopeFormatter,
    private val toolRegistry: BotToolRegistry,
    private val toolSync: BotToolSync,
    private val skillRegistry: BotSkillRegistry,
    private val contextProviders: @JvmSuppressWildcards Set<DeviceContextProvider>,
) : BotSession {

    override val agentId: String = config.agentId
    override val displayName: String = config.displayName

    private val _status = MutableStateFlow(BotStatus.IDLE)
    override val status = _status.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageQueue = MessageQueue<QueuedMessage>()
    private val conversationCache = mutableMapOf<String, String>() // routeKey -> conversationId
    private val conversationMutex = Mutex()
    private val activeSkills by lazy { skillRegistry.resolveEnabledSkills(config.enabledSkills) }
    private val activeSkillPromptFragments by lazy {
        activeSkills.map { skill -> "[${skill.displayName}]\n${skill.promptFragment}" }
            .filter { it.isNotBlank() }
    }

    /** The active context providers for this session (filtered by config). */
    private val activeProviders: List<DeviceContextProvider> by lazy {
        if (config.contextProviders.isEmpty()) {
            contextProviders.toList()
        } else {
            contextProviders.filter { it.providerId in config.contextProviders }
        }
    }

    override suspend fun start() {
        _status.value = BotStatus.STARTING

        val requestedToolNames = when {
            config.enabledSkills.isEmpty() -> null
            else -> activeSkills.flatMap { it.localToolNames }.toSet()
        }
        toolSync.syncTools(agentId, requestedToolNames)

        // Start the message processing loop
        scope.launch {
            while (true) {
                messageQueue.processNext { queued ->
                    try {
                        val response = processMessage(queued.message, queued.conversationId)
                        queued.onComplete(Result.success(response))
                    } catch (e: Exception) {
                        queued.onComplete(Result.failure(e))
                    }
                }
            }
        }

        _status.value = BotStatus.RUNNING
        Log.i(TAG, "Local bot session started for agent $agentId")
    }

    override suspend fun stop() {
        _status.value = BotStatus.STOPPING
        messageQueue.close()
        scope.cancel()
        _status.value = BotStatus.STOPPED
        Log.i(TAG, "Local bot session stopped for agent $agentId")
    }

    override suspend fun sendToAgent(message: ChannelMessage, conversationId: String?): BotResponse {
        val resolvedConversationId = conversationId ?: resolveConversation(message)

        // Use the message queue for serial processing
        val result = CompletableDeferred<Result<BotResponse>>()
        messageQueue.enqueue(QueuedMessage(message, resolvedConversationId) { result.complete(it) })
        return result.await().getOrThrow()
    }

    override fun streamToAgent(message: ChannelMessage, conversationId: String?): Flow<BotResponseChunk> = flow {
        _status.value = BotStatus.PROCESSING
        try {
            val resolvedConversationId = conversationId ?: resolveConversation(message)
            val formattedText = envelopeFormatter.format(
                message = message,
                contextProviders = activeProviders,
                customTemplate = config.envelopeTemplate,
                skillPromptFragments = activeSkillPromptFragments,
            )

            runtimeClient.streamConversationMessage(
                agentId = agentId,
                conversationId = resolvedConversationId,
                input = formattedText,
            ).collect { event ->
                when (event) {
                    is LettaRuntimeEvent.AssistantDelta -> emit(
                        BotResponseChunk(
                            text = event.textDelta,
                            conversationId = event.conversationId,
                        )
                    )

                    is LettaRuntimeEvent.ReasoningDelta -> emit(
                        BotResponseChunk(
                            text = event.textDelta,
                            conversationId = event.conversationId,
                        )
                    )

                    is LettaRuntimeEvent.ToolCallRequested -> {
                        val toolSummary = handleToolCalls(event)
                        emit(
                            BotResponseChunk(
                                text = toolSummary,
                                conversationId = event.conversationId,
                            )
                        )
                    }

                    is LettaRuntimeEvent.RawMessage -> Unit

                    is LettaRuntimeEvent.Completed -> {
                        _status.value = BotStatus.RUNNING
                        emit(
                            BotResponseChunk(
                                conversationId = event.conversationId,
                                isComplete = true,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            _status.value = BotStatus.RUNNING
            Log.e(TAG, "Error streaming message for agent $agentId", e)
            throw e
        }
    }

    override suspend fun deliverToChannel(response: BotResponse, sourceMessage: ChannelMessage): DeliveryResult {
        // The gateway handles delivery to the appropriate channel adapter.
        // This method exists for the interface contract — local sessions
        // don't directly deliver; the gateway routes the response.
        return DeliveryResult.Success()
    }

    override suspend fun resolveConversation(message: ChannelMessage): String {
        val routeKey = when (config.conversationMode) {
            ConversationMode.SHARED -> "shared"
            ConversationMode.PER_CHANNEL -> "channel:${message.channelId}"
            ConversationMode.PER_CHAT -> "chat:${message.channelId}:${message.chatId}"
            ConversationMode.DISABLED -> return createConversation()
        }

        return conversationMutex.withLock {
            conversationCache.getOrPut(routeKey) {
                config.sharedConversationId ?: createConversation()
            }
        }
    }

    /** Process a single message — format, send to agent, parse response. */
    private suspend fun processMessage(message: ChannelMessage, conversationId: String): BotResponse {
        _status.value = BotStatus.PROCESSING

        try {
            val formattedText = envelopeFormatter.format(
                message = message,
                contextProviders = activeProviders,
                customTemplate = config.envelopeTemplate,
                skillPromptFragments = activeSkillPromptFragments,
            )

            var finalText = ""
            var usage: UsageInfo? = null

            runtimeClient.streamConversationMessage(
                agentId = agentId,
                conversationId = conversationId,
                input = formattedText,
            ).collect { event ->
                when (event) {
                    is LettaRuntimeEvent.ToolCallRequested -> {
                        handleToolCalls(event)
                    }

                    is LettaRuntimeEvent.Completed -> {
                        finalText = event.finalText
                        usage = event.usage
                    }

                    else -> Unit
                }
            }

            val parseResult = if (config.directivesEnabled) {
                DirectiveParser.parse(finalText)
            } else {
                DirectiveParser.ParseResult(finalText, emptyList())
            }

            _status.value = BotStatus.RUNNING
            return BotResponse(
                agentId = agentId,
                conversationId = conversationId,
                text = parseResult.cleanText,
                directives = parseResult.directives,
                usage = usage,
            )
        } catch (e: Exception) {
            _status.value = BotStatus.RUNNING
            Log.e(TAG, "Error processing message for agent $agentId", e)
            throw e
        }
    }

    /** Create a new conversation for this agent. */
    private suspend fun createConversation(): String {
        return runtimeClient.createConversation(agentId = agentId)
    }

    private suspend fun handleToolCalls(event: LettaRuntimeEvent.ToolCallRequested): String {
        val results = event.toolCalls.map { toolCall ->
            val toolName = toolCall.name ?: return@map "Skipped unnamed tool call"
            when (val result = toolRegistry.execute(toolName, toolCall.arguments)) {
                is BotToolExecutionResult.Success -> {
                    val toolCallId = toolCall.effectiveId
                    if (toolCallId.isNotBlank()) {
                        runtimeClient.submitToolResult(
                            conversationId = event.conversationId,
                            toolCallId = toolCallId,
                            toolReturn = result.payload,
                        )
                    }
                    "Executed $toolName"
                }

                is BotToolExecutionResult.Unavailable -> {
                    val toolCallId = toolCall.effectiveId
                    if (toolCallId.isNotBlank()) {
                        runtimeClient.submitToolResult(
                            conversationId = event.conversationId,
                            toolCallId = toolCallId,
                            toolReturn = result.reason,
                        )
                    }
                    "Unavailable: $toolName"
                }

                is BotToolExecutionResult.Failure -> {
                    val toolCallId = toolCall.effectiveId
                    if (toolCallId.isNotBlank()) {
                        runtimeClient.submitToolResult(
                            conversationId = event.conversationId,
                            toolCallId = toolCallId,
                            toolReturn = result.error,
                        )
                    }
                    "Failed: $toolName"
                }
            }
        }

        return results.joinToString(separator = "\n")
    }

    @AssistedFactory
    interface Factory {
        fun create(config: BotConfig): LocalBotSession
    }

    private data class QueuedMessage(
        val message: ChannelMessage,
        val conversationId: String,
        val onComplete: (Result<BotResponse>) -> Unit,
    )

    companion object {
        private const val TAG = "LocalBotSession"
    }
}
