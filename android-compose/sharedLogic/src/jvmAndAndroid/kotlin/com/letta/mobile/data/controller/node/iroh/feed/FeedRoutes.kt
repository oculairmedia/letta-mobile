package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.adminError
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.model.resolveConversationReadTarget
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The conversation feed v1 route: `feed.subscribe` reads one conversation's turn journal from a cursor. Register [forHost]
 * on the host's built admin router; it registers the method and advertises `conversation_feed_v1`. The transport's
 * capability matrix gates the method (`chat.read`) before it gets here.
 */
object FeedRoutes {
    /**
     * The route over [journal], the same journal the host's turns write to. [pushes] says a [FeedPusher] runs over it too, so
     * the host also advertises [ConversationFeedWire.PUSH_CAPABILITY].
     */
    fun forHost(journal: FeedJournalStore, pushes: Boolean = false): AdminRpcRoutes = FeedRouteSet(journal, pushes)
}

/**
 * Whether a peer with [context] may read the feed of [agent]'s conversation that it names [conversation], the rule
 * `feed.subscribe` and the live push share. A peer bound to conversations (not unrestricted) may not name the bare `default`:
 * it names no agent, so which journal it selects would come from the request, not from the peer's binding. An agent-scoped
 * peer may read only the journals of agents in its scope.
 */
internal fun mayReadFeed(conversation: String, agent: AgentId?, context: AdminRpcRequestContext): Boolean {
    val bareDefault = conversation == IdFormats.DEFAULT_CONVERSATION_ID
    val bound = context.authorizedConversationIds != null
    val conversationAllowed = context.canAccessConversation(conversation) && !(bareDefault && bound)
    val agentAllowed = agent == null || IrohPeerCapabilities.isAgentInScope(context.authorizedAgentIds, agent.value)
    return conversationAllowed && agentAllowed
}

/**
 * Registers `feed.subscribe`. A request is authorized on three counts: the peer may read the conversation (the
 * conversation-content scope that `message.list` also applies), the agent owning it is in the peer's agent scope, and the
 * journal is read under that owner's key, so a conversation id cannot be borrowed across agents. For an agent-scoped peer an
 * owner that cannot be resolved is a refusal, never an empty page. Telemetry carries the outcome and counts, never an id.
 */
internal class FeedRouteSet(private val journal: FeedJournalStore, private val pushes: Boolean = false) : AdminRpcRoutes {
    override fun register(router: AdminRpcRouter) {
        router.registerScoped(ConversationFeedWire.SUBSCRIBE) { params, context ->
            if (!journal.epochPersisted) refuse(OUTCOME_UNAVAILABLE, UNAVAILABLE)
            val request = FeedRequest.parse(params)
            if (!mayReadFeed(request.conversation.value, null, context)) refuse(OUTCOME_CONVERSATION_SCOPE, DENIED_CONVERSATION)
            val page = pageFor(request, context)
            Telemetry.event(
                TAG, "request.answered", "outcome" attr OUTCOME_OK, "mode" attr page.mode, "entries" attr page.entries.size,
                "hasMore" attr page.hasMore,
            )
            LettaJson.wire.encodeToJsonElement(FeedPage.serializer(), page)
        }
        router.featureCapabilities += ConversationFeedWire.CAPABILITY
        if (pushes) router.featureCapabilities += ConversationFeedWire.PUSH_CAPABILITY
    }

    private fun pageFor(request: FeedRequest, context: AdminRpcRequestContext): FeedPage {
        val owner = owner(request, context.authorizedAgentIds)
            ?: return journal.emptyPage(request.from)
        return journal.page(JournalKey(owner, request.targetConversation), request.from, request.limit)
    }

    /**
     * The agent whose journal is read: the alias's own agent, else the named one, else the one the journal recorded. A scoped
     * peer must name or resolve an in-scope owner, and the named one must be the recorded one; a mismatch is refused, never
     * quietly answered with an empty page that would tell the peer the conversation is someone else's.
     */
    private fun owner(request: FeedRequest, scope: Set<String>?): AgentId? {
        val recorded = journal.ownerOf(request.targetConversation)
        val resolved = request.aliasOwner ?: request.agent ?: recorded
        if (scope == null) return resolved
        val agent = resolved ?: refuse(OUTCOME_OWNER_UNKNOWN, AgentScopeGuard.DENIED)
        val mismatch = recorded != null && recorded != agent
        if (mismatch || !IrohPeerCapabilities.isAgentInScope(scope, agent.value)) refuse(OUTCOME_AGENT_SCOPE, AgentScopeGuard.DENIED)
        return agent
    }

    private fun refuse(outcome: String, message: String): Nothing {
        Telemetry.event(TAG, "request.answered", "outcome" attr outcome)
        adminError(message)
    }

}

private const val TAG = "FeedHost"
private const val OUTCOME_OK = "success"
private const val OUTCOME_AGENT_SCOPE = "agent_scope"
private const val OUTCOME_CONVERSATION_SCOPE = "conversation_scope"
private const val OUTCOME_OWNER_UNKNOWN = "owner_unknown"
private const val OUTCOME_UNAVAILABLE = "epoch_unpersisted"
private const val UNAVAILABLE = "unavailable: the host could not persist its feed epoch"
private const val DENIED_CONVERSATION = "forbidden: conversation out of authorized scope"

/** A checked `feed.subscribe` request. */
internal class FeedRequest private constructor(
    val conversation: ConversationId,
    val agent: AgentId?,
    val from: FeedCursor?,
    val limit: Int,
) {
    /** The conversation as the journal keys it: an alias names the agent's default conversation. */
    val targetConversation: ConversationId = resolveConversationReadTarget(conversation)?.first ?: conversation

    /** The agent a `conv-default-<agent>` alias names, or null for any other conversation id. */
    val aliasOwner: AgentId? = resolveConversationReadTarget(conversation)?.second

    companion object {
        /** Reads [params]; anything malformed is an invalid request. */
        fun parse(params: JsonObject?): FeedRequest {
            val conversation = id(params?.get(ConversationFeedWire.CONVERSATION_ID)) ?: invalid()
            val agent = params?.get(ConversationFeedWire.AGENT_ID)?.let { id(it) ?: invalid() }
            val limit = params?.get(ConversationFeedWire.LIMIT)?.let { limit(it) ?: invalid() } ?: ConversationFeedWire.DEFAULT_LIMIT
            val from = params?.get(ConversationFeedWire.FROM)?.let { cursor(it) ?: invalid() }
            // The bare default names no agent: without agent_id the request cannot say whose journal it wants.
            if (conversation == IdFormats.DEFAULT_CONVERSATION_ID && agent == null) invalid()
            return FeedRequest(ConversationId(conversation), agent?.let(::AgentId), from, limit)
        }

        private fun id(element: JsonElement?): String? {
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            return text.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS }
        }

        private fun limit(element: JsonElement): Int? =
            (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..ConversationFeedWire.MAX_LIMIT }

        private fun cursor(element: JsonElement): FeedCursor? {
            val fields = element as? JsonObject ?: return null
            val epoch = number(fields[ConversationFeedWire.EPOCH]) ?: return null
            val seq = number(fields[ConversationFeedWire.SEQ]) ?: return null
            return FeedCursor(epoch, seq)
        }

        private fun number(element: JsonElement?): Long? =
            (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

        private fun invalid(): Nothing = adminError(ConversationFeedWire.INVALID_REQUEST)

        private const val MAX_ID_CHARS = 256
    }
}
