package com.letta.mobile.data.transport.iroh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * letta-mobile-bn008.2: the direct agent-to-agent message envelope carried over
 * an Iroh QUIC BiStream. QUIC is reliable/ordered/lossless, so delivery is acked
 * (no gap-replay). The receiver dedupes on [msgId] for at-most-once processing on
 * redelivery.
 */
@Serializable
data class IrohAgentMessage(
    val fromAgentId: String,
    val toAgentId: String,
    val body: String,
    val msgId: String,
    val ts: Long,
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
data class IrohAgentMessageAck(val msgId: String, val accepted: Boolean) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(wire: String): IrohAgentMessageAck = Json.decodeFromString(serializer(), wire)
    }
}
