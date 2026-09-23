package com.letta.mobile.data.canvas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject

/**
 * The canvas relay protocol between an app and the host every app on it dials.
 *
 * The host is the rendezvous and the durable owner of shared canvases: it binds one canvas to each
 * [topic] (a conversation, or a standalone canvas), keeps an append-only log of their ops with a
 * cursor, acknowledges each op once it is durable, catches up any app that was away, fans ops out
 * to every other app on the topic and relays presence. Topics are scoped by the host the
 * connection goes to, so the same conversation id on two hosts never meets.
 *
 * Every frame carries [CanvasRelayProtocol.VERSION]; a frame of any other version is refused, never
 * guessed at ([CanvasRelayProtocol.decode]).
 */
object CanvasRelayProtocol {
    const val VERSION: Int = 1

    /** The topic of conversation [conversationId]: every app opening it shares one canvas. */
    fun conversationTopic(conversationId: String): String = "conversation:$conversationId"

    /** The topic of a canvas that belongs to no conversation, shared by its id. */
    fun canvasTopic(canvasId: CanvasId): String = "canvas:${canvasId.value}"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(message: CanvasRelayMessage): String =
        json.encodeToString(CanvasRelayFrame.serializer(), CanvasRelayFrame(VERSION, message))

    /** The message in [text], or why it cannot be read - an unknown version first of all. */
    fun decode(text: String): CanvasRelayDecoded {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return CanvasRelayDecoded.Malformed("not a JSON object")
        val version = runCatching { (root["v"] as? JsonPrimitive)?.int }.getOrNull()
            ?: return CanvasRelayDecoded.Malformed("no protocol version")
        if (version != VERSION) return CanvasRelayDecoded.UnsupportedVersion(version)
        return runCatching { json.decodeFromString(CanvasRelayFrame.serializer(), text).message }
            .fold(
                onSuccess = { CanvasRelayDecoded.Message(it) },
                onFailure = { CanvasRelayDecoded.Malformed(it.message ?: "unreadable frame") },
            )
    }
}

sealed interface CanvasRelayDecoded {
    data class Message(val message: CanvasRelayMessage) : CanvasRelayDecoded
    data class UnsupportedVersion(val version: Int) : CanvasRelayDecoded
    data class Malformed(val reason: String) : CanvasRelayDecoded
}

@Serializable
data class CanvasRelayFrame(val v: Int, val message: CanvasRelayMessage)

@Serializable
sealed interface CanvasRelayMessage {
    // ---- App to host ----

    /**
     * Opens [topic] on this connection. The host binds it to a canvas ([proposedCanvasId] when it is
     * the first to ask), answers [Joined], sends every op after [afterCursor] and then [CaughtUp].
     */
    @Serializable
    @SerialName("join")
    data class Join(val topic: String, val proposedCanvasId: String, val afterCursor: Long = 0L) : CanvasRelayMessage

    /** An op for [topic]; answered by [Ack] once durable on the host, or [Rejected]. */
    @Serializable
    @SerialName("publish")
    data class Publish(val topic: String, val op: CanvasOp) : CanvasRelayMessage

    @Serializable
    @SerialName("leave")
    data class Leave(val topic: String) : CanvasRelayMessage

    /** This app's cursor on [topic]; the host stamps who it is from before passing it on. */
    @Serializable
    @SerialName("presence")
    data class Presence(val topic: String, val presence: CanvasPresence) : CanvasRelayMessage

    // ---- Host to app ----

    /** [topic] is canvas [canvasId] on host [hostId], whose log ends at [head]. */
    @Serializable
    @SerialName("joined")
    data class Joined(val topic: String, val canvasId: String, val hostId: String, val head: Long) : CanvasRelayMessage

    /**
     * An op in the host's log for [topic], at [cursor]. [origin] is who the host received it from,
     * as the host authenticated them; never what the op itself claims.
     */
    @Serializable
    @SerialName("op")
    data class Op(val topic: String, val cursor: Long, val origin: String, val op: CanvasOp) : CanvasRelayMessage

    /** Catch-up for [topic] is complete up to [cursor]. */
    @Serializable
    @SerialName("caught_up")
    data class CaughtUp(val topic: String, val cursor: Long) : CanvasRelayMessage

    /** [opId] is durable on the host at [cursor] ([duplicate]: it already was). */
    @Serializable
    @SerialName("ack")
    data class Ack(val topic: String, val opId: String, val cursor: Long, val duplicate: Boolean = false) : CanvasRelayMessage

    /** [opId] will not be stored ([reason]); the app stops resending it. */
    @Serializable
    @SerialName("rejected")
    data class Rejected(val topic: String, val opId: String, val reason: String) : CanvasRelayMessage

    /** Another app's cursor on [topic], its peer id stamped by the host. */
    @Serializable
    @SerialName("presence_relayed")
    data class PresenceRelayed(val topic: String, val presence: CanvasPresence) : CanvasRelayMessage

    /** [peerId] left [topic] (disconnected or closed it). */
    @Serializable
    @SerialName("presence_gone")
    data class PresenceGone(val topic: String, val peerId: String) : CanvasRelayMessage

    /** The host will not serve this connection ([reason]); it closes after this. */
    @Serializable
    @SerialName("refused")
    data class Refused(val reason: String) : CanvasRelayMessage
}

/**
 * The relay topic [id] syncs under in this store: its conversation's (every app opening that
 * conversation shares one canvas), or its own for a canvas that belongs to no conversation.
 */
suspend fun CanvasDocumentStore.relayTopicOf(id: CanvasId): String =
    get(id)?.conversationId?.takeIf { it.isNotBlank() }?.let(CanvasRelayProtocol::conversationTopic)
        ?: CanvasRelayProtocol.canvasTopic(id)
