package com.letta.mobile.data.node.carrier

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.RequestId
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.appserver.AppServerRequestIdFactory
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How the shell proves who it is in its `auth` frame. */
sealed interface NodeCredential {
    /** A bearer or pairing token, sent as `auth.token`. Never logged and never part of an error. */
    class Token(val secret: String) : NodeCredential {
        override fun toString(): String = "Token(<redacted>)"
    }

    /**
     * No token: the node admits the peer by the identity its carrier verified. The in-process carrier's
     * [NodePeerIdentity.LOCAL_SHELL] is admitted as an admin peer this way.
     */
    data object CarrierIdentity : NodeCredential
}

/** What the shell asks for when it opens a session. */
data class NodeOpenRequest(
    /** Where to dial. */
    val address: NodeAddress,
    /** How to authenticate. */
    val credential: NodeCredential,
    /** A display name the node may show for this peer; null sends none. */
    val peerName: String? = null,
    /** Capabilities this shell advertises in `auth.capabilities` (feature flags such as `app_tool_relay_v1`). */
    val clientCapabilities: Set<String> = setOf(NodeCarrierWire.FRAME_PART_CAPABILITY),
)

/** What the node advertised in its accepted `auth_response`. */
data class NodeCapabilities(
    /** The node's feature capabilities (`conversation_feed_v1`, `frame_part`, ...). */
    val host: Set<String>,
    /** Whether the node reports this peer as paired. */
    val paired: Boolean,
) {
    /** Whether the node advertised [capability]. A shell asks this before reaching for an optional feature. */
    fun supports(capability: String): Boolean = capability in host
}

/**
 * The shell's one client stack onto a node: an authenticated session over any [NodeCarrier]. It speaks the node
 * protocol (auth, admin_rpc, turn commands, feed resume) as frames, so it behaves the same over the in-process and
 * the Iroh carrier, and it holds no reference into the node.
 *
 * Open one with [open]. [close] detaches: turns this session started keep running on the node, and a later session
 * catches up with [resumeFeed] from the last cursor it applied.
 */
class NodeSession private constructor(
    private val link: NodeLink,
    private val router: NodeControlRouter,
    private val requestIds: () -> String,
    /** What the node advertised when it accepted this session. */
    val capabilities: NodeCapabilities,
) {
    /** The node this session reached, as the carrier verified it. */
    val node: NodePeerIdentity get() = link.node

    /** Null while the session's link is open; then why it ended. */
    val closure: StateFlow<NodeLinkClosure?> get() = link.closure

    /**
     * Node pushes and control frames that answer no request of this session (for example an app tool call), in order
     * per lane. Collect once. The session buffers these frames without bound until they are collected, so collect
     * this for the session's whole life (or close the session).
     */
    val inbound: Flow<NodeInbound> get() = router.unsolicited

    /** A request id from this session's source, for a frame the caller builds. */
    fun nextRequestId(): String = requestIds()

    /** Writes [frame] on the control lane without waiting for an answer. */
    suspend fun send(frame: NodeFrame) = link.sendControl(frame)

    /**
     * Writes [frame] (which must carry a `request_id`) on the control lane and returns the control frame the node
     * answered it with: `runtime_start_response`, `input_accepted`, `sync_response`, or an `error` frame (a refused
     * capability, `unauthorized`). Throws [NodeCarrierException] with [NodeCarrierFailure.Closed] if the link ends first.
     */
    suspend fun request(frame: NodeFrame): NodeFrame = router.request(frame)

    /**
     * Calls the one-shot `admin_rpc` [method] with [params] on a call lane of its own and returns the
     * `admin_rpc_response` frame, success or not. Throws [NodeCarrierException] with [NodeCarrierFailure.Closed] if the
     * lane ends without one.
     *
     * For one-shot methods only. It finishes its send half at once, and the node treats a finished send half as the
     * cancel of a streamed method (one that sends `admin_rpc_stream` frames before its response, such as
     * `voice.speech`), so a streamed method called through this is cancelled and its frames are dropped.
     */
    suspend fun adminRpc(method: String, params: JsonObject?): NodeFrame {
        val lane = link.openCall(adminRpcFrame(RequestId(requestIds()), method, params))
        lane.finishSending()
        return lane.incoming.firstOrNull { it.type == NodeCarrierWire.ADMIN_RPC_RESPONSE }
            ?: throw NodeCarrierException(NodeCarrierFailure.Closed(link.closure.value ?: NodeLinkClosure.LOST))
    }

    /**
     * Reads [conversation]'s feed from [from] (null asks for a snapshot) with `feed.subscribe`, page after page while
     * the node says `has_more`, each read continuing from the previous page's cursor. Returns the pages in order; the
     * caller applies them by the feed contract (a `snapshot` page replaces, a `replay` page appends). Throws
     * [NodeCarrierException] with [NodeCarrierFailure.Refused] when the node refuses a read, and with
     * [NodeCarrierFailure.Incomplete] when the node still says `has_more` after [MAX_RESUME_PAGES] pages, so a
     * truncated history never looks complete.
     */
    suspend fun resumeFeed(conversation: ConversationId, agent: AgentId, from: FeedCursor?): List<FeedPage> {
        val pages = mutableListOf<FeedPage>()
        var cursor = from
        do {
            val response = adminRpc(ConversationFeedWire.SUBSCRIBE, feedParams(conversation, agent, cursor))
            val page = decodeFeedPage(response)
            pages += page
            cursor = page.cursor
        } while (page.hasMore && pages.size < MAX_RESUME_PAGES)
        if (pages.last().hasMore) {
            throw NodeCarrierException(NodeCarrierFailure.Incomplete(ConversationFeedWire.SUBSCRIBE, pages.size))
        }
        return pages
    }

    /** Detaches: closes the link normally. Turns keep running on the node. Idempotent. */
    suspend fun close() = link.close(NodeLinkClosure.NORMAL)

    companion object {
        /** A resume gives up after this many pages if the node keeps saying `has_more`, so a broken node cannot loop it. */
        const val MAX_RESUME_PAGES: Int = 256

        /**
         * Dials [request]'s address with [carrier], sends the `auth` frame and returns the accepted session. Its
         * routing runs in [scope] until the link closes. Throws [NodeCarrierException]: [NodeCarrierFailure.Unreachable]
         * when the dial fails, [NodeCarrierFailure.AuthRejected] when the node refuses the credential, and
         * [NodeCarrierFailure.Closed] when the node closes the link before answering (a rate limit may do either). On
         * every exit that does not return a session, cancellation included, the dialled link is closed first.
         */
        suspend fun open(
            carrier: NodeCarrier,
            request: NodeOpenRequest,
            scope: CoroutineScope,
            requestIds: () -> String = AppServerRequestIdFactory("node"),
        ): NodeSession {
            val link = carrier.dial(request.address)
            var session: NodeSession? = null
            try {
                val router = NodeControlRouter.start(link, scope)
                val answer = router.request(authFrame(RequestId(requestIds()), request))
                if (answer.json[NodeCarrierWire.SUCCESS].booleanValue() != true) {
                    val reason = answer.json[NodeCarrierWire.ERROR].stringValue() ?: "denied"
                    throw NodeCarrierException(NodeCarrierFailure.AuthRejected(reason))
                }
                session = NodeSession(link, router, requestIds, capabilitiesOf(answer))
                return session
            } finally {
                // A refusal, a closure or the caller's cancellation: the link has no other owner, so close it here.
                if (session == null) withContext(NonCancellable) { runCatchingCancellable { link.close() } }
            }
        }
    }
}

private fun authFrame(requestId: RequestId, request: NodeOpenRequest): NodeFrame = NodeFrame(
    buildJsonObject {
        put(NodeCarrierWire.TYPE, NodeCarrierWire.AUTH)
        put(NodeCarrierWire.REQUEST_ID, requestId.value)
        (request.credential as? NodeCredential.Token)?.let { put(NodeCarrierWire.TOKEN, it.secret) }
        request.peerName?.let { put(NodeCarrierWire.PEER_NAME, it) }
        putJsonArray(NodeCarrierWire.CAPABILITIES) { request.clientCapabilities.sorted().forEach { add(JsonPrimitive(it)) } }
    },
)

private fun adminRpcFrame(requestId: RequestId, method: String, params: JsonObject?): NodeFrame = NodeFrame(
    buildJsonObject {
        put(NodeCarrierWire.TYPE, NodeCarrierWire.ADMIN_RPC)
        put(NodeCarrierWire.REQUEST_ID, requestId.value)
        put(NodeCarrierWire.METHOD, method)
        params?.let { put(NodeCarrierWire.PARAMS, it) }
    },
)

private fun feedParams(conversation: ConversationId, agent: AgentId, from: FeedCursor?): JsonObject = buildJsonObject {
    put(ConversationFeedWire.CONVERSATION_ID, conversation.value)
    put(ConversationFeedWire.AGENT_ID, agent.value)
    from?.let { cursor ->
        putJsonObject(ConversationFeedWire.FROM) {
            put(ConversationFeedWire.EPOCH, cursor.epoch)
            put(ConversationFeedWire.SEQ, cursor.seq)
        }
    }
}

private fun decodeFeedPage(response: NodeFrame): FeedPage {
    val result = response.json[NodeCarrierWire.RESULT] as? JsonObject
    if (response.json[NodeCarrierWire.SUCCESS].booleanValue() != true || result == null) {
        val error = response.json[NodeCarrierWire.ERROR].stringValue() ?: "unknown"
        throw NodeCarrierException(NodeCarrierFailure.Refused(ConversationFeedWire.SUBSCRIBE, error))
    }
    return LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), result)
}

private fun capabilitiesOf(answer: NodeFrame): NodeCapabilities = NodeCapabilities(
    host = (answer.json[NodeCarrierWire.CAPABILITIES] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        .orEmpty(),
    paired = answer.json[NodeCarrierWire.PAIRED].booleanValue() == true,
)

private fun JsonElement?.booleanValue(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull
