package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.model.visibleInLists
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.appserver.AppServerAdminMethod
import com.letta.mobile.data.transport.appserver.AppServerMessagePage
import com.letta.mobile.data.transport.appserver.decodeAppServerMessageList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A directory read: its value, or the [DeliveryFailure] to report. */
internal sealed interface Lookup<out T> {
    data class Found<T>(val value: T) : Lookup<T>

    data class Missing(val failure: DeliveryFailure) : Lookup<Nothing>
}

internal inline fun <T, R> Lookup<T>.then(next: (T) -> Lookup<R>): Lookup<R> = when (this) {
    is Lookup.Found -> next(value)
    is Lookup.Missing -> this
}

/**
 * letta-mobile-jvctq: the `admin_rpc` reads a swarm client makes, all inside the device's scope.
 * The host filters agent listings to the device's agent scope; conversation reads by id are NOT
 * scope-checked by the host yet (letta-mobile-dpv7m.4), so [conversationOf] refuses, here, any
 * conversation whose owner is not the agent the caller resolved in scope.
 */
internal class SwarmDirectory(private val session: SwarmSession) {
    /** The agents this device may use, paged up to [SwarmLimits.MAX_AGENTS]. */
    suspend fun agents(): Lookup<List<SwarmAgent>> {
        val collected = mutableListOf<SwarmAgent>()
        while (collected.size < SwarmLimits.MAX_AGENTS) {
            val params = buildJsonObject {
                put("limit", AGENT_PAGE.toString())
                put("offset", collected.size.toString())
            }
            val page = when (val read = decoded(AGENT_LIST, params, ListSerializer(Agent.serializer()))) {
                is Lookup.Missing -> return read
                is Lookup.Found -> read.value.orEmpty()
            }
            val fresh = page.visibleInLists().map { SwarmAgent(it.id, it.name) }.filter { agent -> collected.none { it.id == agent.id } }
            collected += fresh
            if (page.size < AGENT_PAGE || fresh.isEmpty()) break
        }
        return Lookup.Found(collected.take(SwarmLimits.MAX_AGENTS))
    }

    suspend fun resolve(ref: AgentRef): Lookup<SwarmAgent> = agents().then { it.resolve(ref) }

    /** [conversation], if it belongs to [agent]. */
    suspend fun conversationOf(agent: SwarmAgent, conversation: ConversationId): Lookup<ConversationId> {
        val params = buildJsonObject { put("conversation_id", conversation.value) }
        return when (val read = decoded(CONVERSATION_GET, params, Conversation.serializer())) {
            is Lookup.Missing -> Lookup.Missing(DeliveryFailure.ConversationNotFound(conversation, read.failure.reasonText()))
            is Lookup.Found -> {
                val found = read.value ?: return Lookup.Missing(DeliveryFailure.ConversationNotFound(conversation, "no result"))
                if (found.agentId != agent.id) {
                    Lookup.Missing(DeliveryFailure.ConversationOutOfScope(conversation, found.agentId))
                } else {
                    Lookup.Found(found.id)
                }
            }
        }
    }

    suspend fun createConversation(agent: SwarmAgent): Lookup<ConversationId> {
        val params = buildJsonObject { put("agent_id", agent.id.value) }
        return when (val read = decoded(CONVERSATION_CREATE, params, Conversation.serializer())) {
            is Lookup.Missing -> read
            is Lookup.Found -> read.value?.let { Lookup.Found(it.id) }
                ?: Lookup.Missing(DeliveryFailure.HostError(CONVERSATION_CREATE.value, "no result"))
        }
    }

    /** Up to [limit] messages of [conversation] after [after] (oldest first), or the newest [limit] when [after] is null. */
    suspend fun messages(conversation: ConversationId, after: MessageId?, limit: Int): Lookup<List<LettaMessage>> {
        val bounded = limit.coerceIn(1, SwarmLimits.MAX_READ_MESSAGES)
        val page = if (after != null) {
            AppServerMessagePage(limit = bounded, after = after.value, order = "asc")
        } else {
            AppServerMessagePage(limit = bounded, order = "desc")
        }
        return when (val outcome = session.admin(MESSAGE_LIST, page.params(conversation))) {
            is AdminOutcome.Failed -> Lookup.Missing(DeliveryFailure.HostError(MESSAGE_LIST.value, outcome.error))
            is AdminOutcome.Ok -> decodeOrFailure(MESSAGE_LIST) { decodeAppServerMessageList(outcome.result) }
                .then { list -> Lookup.Found(if (after == null) list.asReversed() else list) }
        }
    }

    /** The newest message id of [conversation]: the anchor a send records before its input leaves. */
    suspend fun latestMessageId(conversation: ConversationId): Lookup<MessageId?> =
        messages(conversation, after = null, limit = 1).then { Lookup.Found(it.lastOrNull()?.id?.let(::MessageId)) }

    private suspend fun <T> decoded(method: AppServerAdminMethod, params: JsonObject, serializer: KSerializer<T>): Lookup<T?> =
        when (val outcome = session.admin(method, params)) {
            is AdminOutcome.Failed -> Lookup.Missing(DeliveryFailure.HostError(method.value, outcome.error))
            is AdminOutcome.Ok -> decodeOrFailure(method) { outcome.result?.decodeAs(serializer) }
        }

    private inline fun <T> decodeOrFailure(method: AppServerAdminMethod, decode: () -> T): Lookup<T> = try {
        Lookup.Found(decode())
    } catch (malformed: SerializationException) {
        Lookup.Missing(DeliveryFailure.HostError(method.value, "unreadable result: ${malformed.message}"))
    } catch (malformed: IllegalArgumentException) {
        Lookup.Missing(DeliveryFailure.HostError(method.value, "unreadable result: ${malformed.message}"))
    }

    private fun <T> JsonElement.decodeAs(serializer: KSerializer<T>): T = LettaJson.adminRpc.decodeFromJsonElement(serializer, this)

    companion object {
        private const val AGENT_PAGE = 50
        private val AGENT_LIST = AppServerAdminMethod("agent.list")
        private val CONVERSATION_GET = AppServerAdminMethod("conversation.get")
        private val CONVERSATION_CREATE = AppServerAdminMethod("conversation.create")
        private val MESSAGE_LIST = AppServerAdminMethod("message.list")
    }
}

/** Resolves [ref] by exact id first, then by case-insensitive name. */
fun List<SwarmAgent>.resolveAgent(ref: AgentRef): AgentResolution {
    firstOrNull { it.id.value == ref.value }?.let { return AgentResolution.Found(it) }
    val named = filter { it.name.equals(ref.value.trim(), ignoreCase = true) }
    return when (named.size) {
        0 -> AgentResolution.NotFound
        1 -> AgentResolution.Found(named.single())
        else -> AgentResolution.Ambiguous(named)
    }
}

sealed interface AgentResolution {
    data class Found(val agent: SwarmAgent) : AgentResolution

    data object NotFound : AgentResolution

    data class Ambiguous(val matches: List<SwarmAgent>) : AgentResolution
}

private fun List<SwarmAgent>.resolve(ref: AgentRef): Lookup<SwarmAgent> = when (val resolved = resolveAgent(ref)) {
    is AgentResolution.Found -> Lookup.Found(resolved.agent)
    AgentResolution.NotFound -> Lookup.Missing(DeliveryFailure.AgentNotFound(ref))
    is AgentResolution.Ambiguous -> Lookup.Missing(DeliveryFailure.AgentAmbiguous(ref, resolved.matches))
}

private fun DeliveryFailure.reasonText(): String = when (this) {
    is DeliveryFailure.HostError -> reason
    else -> toString()
}

/** The plain text of a user or assistant message (other types have none here). */
internal val LettaMessage.plainText: String?
    get() = when (this) {
        is UserMessage -> content
        is AssistantMessage -> content
        else -> null
    }

/** The swarm-visible form of one history row, bounded to [SwarmLimits.MAX_READ_CONTENT_CHARS]. */
data class SwarmMessage(
    val id: MessageId,
    val type: String,
    val date: String?,
    /** Sender metadata as the host stamped it on the row, when it carries any (letta-mobile-vqyr2). */
    val senderId: String?,
    val clientMessageId: String?,
    val text: String?,
    val truncated: Boolean,
)

internal fun LettaMessage.toSwarmMessage(): SwarmMessage {
    val full = plainText
    return SwarmMessage(
        id = MessageId(id),
        type = messageType,
        date = date,
        senderId = senderId,
        clientMessageId = otid,
        text = full?.take(SwarmLimits.MAX_READ_CONTENT_CHARS),
        truncated = (full?.length ?: 0) > SwarmLimits.MAX_READ_CONTENT_CHARS,
    )
}
