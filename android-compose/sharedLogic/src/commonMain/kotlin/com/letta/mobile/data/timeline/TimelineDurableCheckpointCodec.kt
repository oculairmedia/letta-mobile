package com.letta.mobile.data.timeline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned shared wire format; hosts persist bytes without interpreting continuation policy. */
object TimelineDurableCheckpointCodec {
    @Serializable
    private data class Wire(val version: Int = 1, val revision: Long, val kind: String, val identity: String?, val hasMore: Boolean)

    fun encode(checkpoint: TimelineDurableCheckpoint): ByteArray {
        require(checkpoint.revision >= 0)
        val (kind, identity) = when (val cursor = checkpoint.continuation) {
            null -> "none" to null
            TimelineContinuation.Initial -> "initial" to null
            is TimelineContinuation.Before -> "before" to cursor.messageId.value
            is TimelineContinuation.After -> "after" to cursor.messageId.value
        }
        return Json.encodeToString(Wire.serializer(), Wire(1, checkpoint.revision, kind, identity, checkpoint.hasMore)).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): TimelineDurableCheckpoint {
        val wire = Json.decodeFromString(Wire.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        require(wire.version == 1 && wire.revision >= 0)
        val continuation = when (wire.kind) {
            "none" -> { require(wire.identity == null); null }
            "initial" -> { require(wire.identity == null); TimelineContinuation.Initial }
            "before" -> TimelineContinuation.Before(TimelineMessageId(requireNotNull(wire.identity)))
            "after" -> TimelineContinuation.After(TimelineMessageId(requireNotNull(wire.identity)))
            else -> error("Unknown timeline continuation")
        }
        require(wire.hasMore || continuation == null)
        return TimelineDurableCheckpoint(wire.revision, continuation, wire.hasMore)
    }
}
