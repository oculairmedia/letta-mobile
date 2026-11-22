package com.letta.mobile.data.shellstate.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

/** A `shell_state_updated` [frame] and the [connection] that delivered it (a [ShellStateLink.connection] identity). */
class ShellStatePush(val frame: JsonObject, val connection: String?)

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

    /**
     * [call] bound to [connection] (an identity this link reported): a link whose transport can be replaced must send it
     * over that connection or not at all, so a request built on one connection's document never reaches another's. When
     * [connection] is no longer the one that is up, nothing is sent and the reply is a failure without a code (the host is
     * unreachable as far as that request is concerned). The default suits a link that has one connection for its life.
     */
    suspend fun callOn(connection: String?, method: String, params: JsonObject): ShellStateReply =
        if (connection == currentConnection) call(method, params) else ShellStateReply(success = false)

    /**
     * [pushes], each with the connection that delivered it, decided where the frame arrived and not where it is read:
     * a frame buffered behind a replaced transport still names the connection it came from. The default suits a link
     * that has one connection for its life.
     */
    val originatedPushes: Flow<ShellStatePush> get() = pushes.map { ShellStatePush(it, currentConnection) }
}
