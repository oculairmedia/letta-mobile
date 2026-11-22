package com.letta.mobile.data.feed.client

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The host's answer to one `feed.subscribe`: [result] when it succeeded, else its [error] code. */
class FeedReply(
    /** Whether the request succeeded. */
    val success: Boolean,
    /** The page; null on failure. */
    val result: JsonElement? = null,
    /** The failure's `error` string; null on success. */
    val error: String? = null,
)

/**
 * The part of the host connection the feed client uses. The app binds it to its channel transport with `channelFeedLink`;
 * tests stand in a host that rejects exactly what the real one rejects.
 */
interface FeedLink {
    /** The feature capabilities the connected host advertised; empty while disconnected. */
    val hostCapabilities: Set<String>

    /**
     * The identity of the connection that is up, or null while there is none. Any change of it is a new connection, and a
     * new connection re-reads the feed from the cursor.
     */
    val connection: Flow<String?>

    /** Every `feed_delta` frame the host pushes, raw. */
    val pushes: Flow<JsonObject>

    /** One `admin_rpc` call with [params]: the host's reply, failed or not. Throws when the link itself fails. */
    suspend fun call(method: String, params: JsonObject): FeedReply
}
