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
 * persists the wrapper default when that setting is absent, then checks only a
 * bounded newest message page for an already-recorded provider overflow. An
 * overflow is repaired through Letta Code's own conversation_compact command.
 */
class AppServerContextWindowPreflight(
    private val client: AppServerClient,
    private val defaultContextWindowLimit: Int = DEFAULT_APP_SERVER_CONTEXT_WINDOW_LIMIT,
    private val recentMessageLimit: Int = 20,
    private val requestIdFactory: () -> String = {
        "context-preflight-${nextContextPreflightRequestId.incrementAndGet()}"
    },
) : TurnContextPreflight {
    init {
        require(defaultContextWindowLimit > 0)
        require(recentMessageLimit > 0)
    }

    override suspend fun prepare(agentId: String, conversationId: String): TurnContextPreflightResult {
        val retrieve = client.agentRetrieve(
            AppServerCommand.AgentRetrieve(requestIdFactory(), agentId),
        )
        check(retrieve.success && retrieve.agent != null) {
            "agent_retrieve failed: ${retrieve.error ?: "missing agent"}"
        }
        val conversation = client.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(requestIdFactory(), conversationId),
        )
        check(conversation.success && conversation.conversation != null) {
            "conversation_retrieve failed: ${conversation.error ?: "missing conversation"}"
        }

        val existingLimit = retrieve.agent.contextWindowLimit()
        val configured = existingLimit == null
        val agentLimit = existingLimit ?: defaultContextWindowLimit.also { limit ->
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
        }
        val effectiveLimit = conversation.conversation.contextWindowLimit() ?: agentLimit
        val activeMessageIds = conversation.conversation.activeMessageIds()

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

        val shouldCompact = messages.messages.any { message ->
            message.isActive(activeMessageIds) && message.recordsProviderOverflow(effectiveLimit)
        }
        if (shouldCompact) {
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

        return TurnContextPreflightResult(
            configuredContextLimit = configured,
            compacted = shouldCompact,
        )
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

private fun JsonElement.recordsProviderOverflow(contextWindowLimit: Int): Boolean {
    val objects = allObjects()
    return objects.hasLengthStop() &&
        (objects.hasInputAtOrBeyond(contextWindowLimit) || objects.hasEmptyAssistant())
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
