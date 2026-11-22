package com.letta.mobile.data.feed.channel

import com.letta.mobile.data.feed.client.FeedLink
import com.letta.mobile.data.feed.client.FeedReply
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject

/** The feed link over a session's channel transport: one `admin_rpc` per read, and the host's `feed_delta` pushes. */
fun channelFeedLink(transport: IChannelTransport): FeedLink = ChannelFeedLink(transport)

private class ChannelFeedLink(private val transport: IChannelTransport) : FeedLink {
    override val hostCapabilities: Set<String> get() = transport.hostCapabilities

    override val connection: Flow<String?> = flow {
        emitAll(
            transport.state.map { state ->
                (state as? ChannelTransportState.Connected)?.let { "${it.sessionId}#${it.connectionEpoch}" }
            },
        )
    }

    override val pushes: Flow<JsonObject> = flow {
        emitAll(
            transport.events.mapNotNull { frame ->
                (frame as? ServerFrame.Unknown)?.takeIf { it.type == ConversationFeedWire.FRAME_TYPE }?.raw
            },
        )
    }

    override suspend fun call(method: String, params: JsonObject): FeedReply =
        transport.adminRpc(method = method, path = "", body = params.toString())
            .let { FeedReply(success = it.success, result = it.result, error = it.error) }
}

/**
 * The feed of [conversation] over [transport], or null when its host does not serve and push the feed: the caller then keeps
 * the path it had. [conversation] is the id the client views the conversation by.
 */
fun channelFeedTimelineSource(
    transport: IChannelTransport,
    agent: com.letta.mobile.data.model.AgentId,
    conversation: com.letta.mobile.data.model.ConversationId,
    scope: kotlinx.coroutines.CoroutineScope,
    sink: suspend (com.letta.mobile.data.model.LettaMessage) -> Unit = {},
): com.letta.mobile.data.feed.client.FeedTimelineSource? {
    val link = channelFeedLink(transport)
    return com.letta.mobile.data.feed.client.FeedTimelineSource(link, agent, conversation, scope, sink)
        .takeIf { com.letta.mobile.data.feed.client.FeedTimelineSource.isAvailable(link) }
}

/**
 * The rows a chat presentation draws for [conversation] when [transport]'s host serves and pushes the feed, or null when it
 * does not (the presentation then reads the ledger, unchanged). The source is built and started in the scope it is given
 * (the presentation's own), so it stops when the presentation closes, and a conversation switch rebinds by opening a new one.
 */
fun channelFeedRows(
    transport: IChannelTransport,
    agent: com.letta.mobile.data.model.AgentId,
    conversation: com.letta.mobile.data.model.ConversationId,
): ((kotlinx.coroutines.CoroutineScope) -> kotlinx.coroutines.flow.StateFlow<List<com.letta.mobile.data.chat.projection.ChatRenderItem>?>)? =
    com.letta.mobile.data.feed.client.FeedTimelineSource.rowsFor(channelFeedLink(transport), agent, conversation)
