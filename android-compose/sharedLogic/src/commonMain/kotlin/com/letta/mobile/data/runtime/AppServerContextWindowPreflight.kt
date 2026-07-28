package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.atomicfu.atomic
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
        val agent = retrieveAgent(agentId)
        val conversation = retrieveConversation(conversationId)
        val existingLimit = agent.contextWindowLimit()
        val configured = existingLimit == null
        val agentLimit = existingLimit ?: persistDefaultContextLimit(agentId)
        val effectiveLimit = conversation.contextWindowLimit() ?: agentLimit
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
    return allObjects().any { objectValue ->
        objectValue.string("id")?.let(activeMessageIds::contains) == true
    }
}

private fun JsonElement.isAssistantMessage(): Boolean =
    allObjects().any { it.string("role") == "assistant" }

private fun JsonElement.recordsPoisonedOrTokenOverflow(contextWindowLimit: Int): Boolean {
    val objects = allObjects()
    return objects.hasEmptyAssistant() || objects.hasInputAtOrBeyond(contextWindowLimit)
}

/**
 * `stop_reason=length` alone is not overflow: providers also emit it when output
 * hits `max_tokens`. Require near-capacity input (half the configured window) so
 * a real 128k provider stop under a 200k wrapper default still compacts, while a
 * low-input max_tokens truncation does not.
 */
private fun JsonElement.recordsLengthStopNearCapacity(contextWindowLimit: Int): Boolean {
    val objects = allObjects()
    if (!objects.hasLengthStop()) return false
    val nearCapacity = (contextWindowLimit / 2).coerceAtLeast(1)
    return objects.hasInputAtOrBeyond(nearCapacity)
}

private fun List<JsonObject>.hasLengthStop(): Boolean =
    any {
        it.string("stop_reason") == "length" || it.string("stopReason") == "length"
    }

private fun List<JsonObject>.hasInputAtOrBeyond(contextWindowLimit: Int): Boolean =
    any { objectValue ->
        val usage = objectValue.objectValue("usage") ?: objectValue
        val input = usage.long("input")
            ?: usage.long("input_tokens")
            ?: usage.long("inputTokens")
        val cacheRead = usage.long("cache_read")
            ?: usage.long("cacheRead")
            ?: 0L
        input != null && input + cacheRead >= contextWindowLimit.toLong()
    }

private fun List<JsonObject>.hasEmptyAssistant(): Boolean =
    any { objectValue ->
        val role = objectValue.string("role")
        val content = objectValue["content"] ?: objectValue["parts"]
        role == "assistant" && (content == JsonNull || content == JsonArray(emptyList()))
    }

private fun JsonElement.allObjects(): List<JsonObject> {
    val found = mutableListOf<JsonObject>()
    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                found += element
                element.values.forEach(::visit)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }
    visit(this)
    return found
}

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
