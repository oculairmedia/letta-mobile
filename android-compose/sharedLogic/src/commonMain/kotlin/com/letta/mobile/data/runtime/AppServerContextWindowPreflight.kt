package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT: Int = 200_000
private val nextContextPreflightRequestId = atomic(0)

/**
 * Uses App Server V2 APIs to keep Letta Code's sliding-window compaction active.
 *
 * Letta Code cannot calculate context usage for custom providers when neither
 * the agent nor conversation has an explicit context window. This preflight
 * persists the wrapper default when that setting is absent, then checks a
 * bounded newest message page for poisoned/overflow evidence. Empty assistant
 * rows and input at/beyond the configured limit always compact. A provider
 * `stop_reason=length` only counts when paired with near-capacity input on the
 * newest active assistant (avoids max_tokens truncation storms). Overflow is
 * repaired through Letta Code's own conversation_compact command.
 */
class AppServerContextWindowPreflight(
    private val client: AppServerClient,
    private val defaultContextWindowLimit: Int = DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT,
    private val recentMessageLimit: Int = 50,
    private val requestIdFactory: () -> String = {
        "context-preflight-${nextContextPreflightRequestId.incrementAndGet()}"
    },
) : TurnContextPreflight {
    init {
        require(defaultContextWindowLimit > 0)
        require(recentMessageLimit > 0)
    }

    override suspend fun prepare(agentId: String, conversationId: String): TurnContextPreflightResult {
        val (agent, conversation) = coroutineScope {
            val agentDeferred = async { retrieveAgent(agentId) }
            val conversationDeferred = async { retrieveConversation(conversationId) }
            agentDeferred.await() to conversationDeferred.await()
        }
        val existingAgentLimit = agent.contextWindowLimit()
        val conversationLimit = conversation.contextWindowLimit()
        // Only stamp an agent-wide default when NEITHER scope has an explicit
        // limit. A conversation override must not mutate sibling inheritance.
        val configured = existingAgentLimit == null && conversationLimit == null
        val agentLimit = existingAgentLimit
            ?: if (configured) persistDefaultContextLimit(agentId) else null
        val effectiveLimit = conversationLimit ?: agentLimit
            ?: error("context preflight resolved no limit after configuration")
        val activeMessageIds = conversation.activeMessageIds()
        val shouldCompact = recentMessagesOverflow(conversationId, effectiveLimit, activeMessageIds)
        if (shouldCompact) compactConversation(agentId, conversationId)
        return TurnContextPreflightResult(
            configuredContextLimit = configured,
            compacted = shouldCompact,
        )
    }

    private suspend fun retrieveAgent(agentId: String): JsonObject {
        val retrieve = client.agentRetrieve(AppServerCommand.AgentRetrieve(requestIdFactory(), agentId))
        check(retrieve.success && retrieve.agent != null) {
            "agent_retrieve failed: ${retrieve.error ?: "missing agent"}"
        }
        return retrieve.agent
    }

    private suspend fun retrieveConversation(conversationId: String): JsonObject {
        val conversation = client.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(requestIdFactory(), conversationId),
        )
        check(conversation.success && conversation.conversation != null) {
            "conversation_retrieve failed: ${conversation.error ?: "missing conversation"}"
        }
        return conversation.conversation
    }

    private suspend fun persistDefaultContextLimit(agentId: String): Int {
        val limit = defaultContextWindowLimit
        val update = client.agentUpdate(
            AppServerCommand.AgentUpdate(
                requestId = requestIdFactory(),
                agentId = agentId,
                body = buildJsonObject { put("context_window_limit", limit) },
            ),
        )
        check(update.success) {
            "agent_update context window failed: ${update.error ?: "unknown error"}"
        }
        return limit
    }

    private suspend fun recentMessagesOverflow(
        conversationId: String,
        effectiveLimit: Int,
        activeMessageIds: Set<String>?,
    ): Boolean {
        val messages = client.conversationMessagesList(
            AppServerCommand.ConversationMessagesList(
                requestId = requestIdFactory(),
                conversationId = conversationId,
                query = buildJsonObject {
                    put("limit", recentMessageLimit)
                    put("order", "desc")
                },
            ),
        )
        check(messages.success && messages.messages != null) {
            "conversation_messages_list failed: ${messages.error ?: "missing messages"}"
        }
        val active = messages.messages.filter { it.isActive(activeMessageIds) }
        if (active.any { it.recordsPoisonedOrTokenOverflow(effectiveLimit) }) return true
        // Length-stop is only consulted on the newest active assistant so an
        // older max_tokens/context stop cannot re-trigger compaction every turn.
        val newestAssistant = active.firstOrNull { it.isAssistantMessage() } ?: return false
        return newestAssistant.recordsLengthStopNearCapacity(effectiveLimit)
    }

    private suspend fun compactConversation(agentId: String, conversationId: String) {
        val compact = client.conversationCompact(
            AppServerCommand.ConversationCompact(
                requestId = requestIdFactory(),
                conversationId = conversationId,
                body = buildJsonObject {
                    put("agent_id", agentId)
                    put(
                        "compaction_settings",
                        buildJsonObject {
                            put("mode", "sliding_window")
                            put("sliding_window_percentage", 0.3)
                        },
                    )
                },
            ),
        )
        check(compact.success) {
            "conversation_compact failed: ${compact.error ?: "unknown error"}"
        }
    }
}

private fun JsonObject.contextWindowLimit(): Int? =
    integer("context_window_limit")
        ?: integer("contextWindowLimit")
        ?: objectValue("model_settings")?.integer("context_window_limit")
        ?: objectValue("modelSettings")?.integer("contextWindowLimit")
        ?: objectValue("llm_config")?.integer("context_window")
        ?: objectValue("llm_config")?.integer("context_window_limit")
        ?: objectValue("llmConfig")?.integer("contextWindow")
        ?: objectValue("llmConfig")?.integer("contextWindowLimit")

private fun JsonObject.activeMessageIds(): Set<String>? {
    val ids = (this["in_context_message_ids"] ?: this["inContextMessageIds"]) as? JsonArray ?: return null
    return ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
}

private fun JsonElement.isActive(activeMessageIds: Set<String>?): Boolean {
    if (activeMessageIds == null) return true
    return inspectMessage().id?.let(activeMessageIds::contains) == true
}

private fun JsonElement.isAssistantMessage(): Boolean =
    inspectMessage().role == "assistant"

private fun JsonElement.recordsPoisonedOrTokenOverflow(contextWindowLimit: Int): Boolean {
    val probe = inspectMessage()
    val tokens = probe.inputTokens
    return probe.hasEmptyAssistant || (tokens != null && tokens >= contextWindowLimit.toLong())
}

/**
 * `stop_reason=length` alone is not overflow: providers also emit it when output
 * hits `max_tokens`. Require near-capacity input (half the configured window) so
 * a real 128k provider stop under a 200k wrapper default still compacts, while a
 * low-input max_tokens truncation does not.
 */
private fun JsonElement.recordsLengthStopNearCapacity(contextWindowLimit: Int): Boolean {
    val probe = inspectMessage()
    if (!probe.hasLengthStop) return false
    val nearCapacity = (contextWindowLimit / 2).coerceAtLeast(1)
    val tokens = probe.inputTokens ?: return false
    return tokens >= nearCapacity.toLong()
}

/**
 * Single-pass envelope probe for preflight checks. Prefer top-level / usage
 * fields on the message object; only DFS nested objects when scanning for
 * provider stop metadata that may live under a nested assistant payload.
 */
private data class MessageProbe(
    val id: String?,
    val role: String?,
    val hasEmptyAssistant: Boolean,
    val hasLengthStop: Boolean,
    /** Null when the message carries no input token count. */
    val inputTokens: Long?,
)

private fun JsonElement.inspectMessage(): MessageProbe {
    val root = this as? JsonObject
    val id = root?.string("id")
    val role = root?.string("role")
    val content = root?.get("content") ?: root?.get("parts")
    val hasEmptyAssistant =
        role == "assistant" && (content == JsonNull || content == JsonArray(emptyList()))
    val inputTokens = readInputTokens(root)
    val hasLengthStop = hasProviderLengthStop(this)
    return MessageProbe(
        id = id,
        role = role,
        hasEmptyAssistant = hasEmptyAssistant,
        hasLengthStop = hasLengthStop,
        inputTokens = inputTokens,
    )
}

private fun readInputTokens(root: JsonObject?): Long? {
    val providerResult = root?.objectValue("provider_result")
    val usage = root?.objectValue("usage")
        ?: providerResult?.objectValue("usage")
        ?: root
    val input = usage?.long("input")
        ?: usage?.long("input_tokens")
        ?: usage?.long("inputTokens")
        ?: return null
    val cacheRead = usage?.long("cache_read")
        ?: usage?.long("cacheRead")
        ?: 0L
    return input + cacheRead
}

private fun hasProviderLengthStop(element: JsonElement): Boolean {
    when (element) {
        is JsonObject -> {
            val stop = element.string("stop_reason") ?: element.string("stopReason")
            if (stop == "length") return true
            for ((key, value) in element) {
                if (shouldWalkForLengthStop(key, value) && hasProviderLengthStop(value)) return true
            }
        }
        is JsonArray -> element.forEach { if (hasProviderLengthStop(it)) return true }
        else -> Unit
    }
    return false
}

private fun shouldWalkForLengthStop(key: String, value: JsonElement): Boolean {
    if (value !is JsonObject && value !is JsonArray) return false
    if (key == "message" || key == "stop" || key == "metadata" ||
        key == "provider" || key == "provider_result" || key == "response"
    ) {
        return true
    }
    val obj = value as? JsonObject ?: return false
    return "stop_reason" in obj || "stopReason" in obj || "role" in obj || "usage" in obj
}

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
