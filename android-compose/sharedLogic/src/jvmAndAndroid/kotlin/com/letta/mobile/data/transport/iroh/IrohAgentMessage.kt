package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.lanes.reply.ReplyTo
import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The direct agent-to-agent message envelope carried over
 * an Iroh QUIC BiStream. QUIC is reliable/ordered/lossless, so delivery is acked
 * (no gap-replay). The receiver dedupes on ([fromAgentId], [msgId]) for at-most-once processing on
 * redelivery.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IrohAgentMessage(
    val fromAgentId: String,
    val toAgentId: String,
    val body: String,
    val msgId: String,
    val ts: Long,
    /** Optional explicit target conversation on the recipient. */
    val conversationId: String? = null,
    /** Optional destination lane. */
    val lane: String? = null,
    /** Task correlation carried with the message. */
    val correlation: Map<String, String>? = null,
    /** Reply correlation, distinct from free-form task correlation. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("correlation_id")
    val correlationId: String? = null,
    /** Host-owned return address advertised by a request. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("reply_to")
    val replyTo: ReplyTo? = null,
    /** Keeps the request open for further correlated replies. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val more: Boolean? = null,
) {
    fun encode(): String = wireJson.encodeToString(serializer(), this)

    companion object {
        /** ALPN for the direct agent-messaging protocol (distinct from the app-server ALPN). */
        val ALPN: ByteArray = "/letta/a2a/0".encodeToByteArray()

        private val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun decode(wire: String): IrohAgentMessage = wireJson.decodeFromString(serializer(), wire)
    }
}

/** Result of an ack read from the peer. */
@Serializable
data class IrohAgentMessageAck(
    val msgId: String,
    val accepted: Boolean,
    /** Transport acceptance is not application delivery. */
    val applicationDelivered: Boolean = false,
    val reason: String? = null,
) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(wire: String): IrohAgentMessageAck = Json.decodeFromString(serializer(), wire)
    }
}
