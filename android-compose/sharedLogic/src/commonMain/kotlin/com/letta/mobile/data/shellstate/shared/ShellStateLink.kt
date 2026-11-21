package com.letta.mobile.data.shellstate.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The host's answer to one shell-state request: [result] when it succeeded, else its [error] code. */
class ShellStateReply(
    /** Whether the request succeeded. */
    val success: Boolean,
    /** The result; null on failure. */
    val result: JsonElement? = null,
    /** The failure's `error` string; null on success. */
    val error: String? = null,
)

/**
 * The part of the host connection the shared shell state uses. The app binds it to its channel
 * transport with `channelShellStateLink`; tests stand in a fake host.
 */
interface ShellStateLink {
    /** The feature capabilities the connected host advertised; empty while disconnected. */
    val hostCapabilities: Set<String>

    /**
     * The identity of the connection that is up right now, read synchronously: the value [connection] last
     * emitted, or null while there is none. A caller that holds a document or a request across a suspension
     * compares this to the identity it started with, so it never acts on another connection's state before the
     * collector that follows [connection] has run.
     */
    val currentConnection: String?

    /** Whether the host is connected right now: the same as [currentConnection] being set. */
    val isConnected: Boolean get() = currentConnection != null

    /**
     * The identity of the connection that is up, or null while there is none: its session id and its connection
     * epoch. A session id is the endpoint's, shared by every reconnect to it, so the epoch (which goes up on each
     * connect) is what tells two connections apart. It is the identity, not a yes or no, that is observed: a drop
     * and a reconnect queued before a collector runs leave no null in between, but the identity differs, and any
     * change of it is a new connection.
     */
    val connection: Flow<String?>

    /** Every `shell_state_updated` frame the host pushes, raw. */
    val pushes: Flow<JsonObject>

    /** One `admin_rpc` call with [params]: the host's reply, failed or not. Throws when the link itself fails. */
    suspend fun call(method: String, params: JsonObject): ShellStateReply
}
