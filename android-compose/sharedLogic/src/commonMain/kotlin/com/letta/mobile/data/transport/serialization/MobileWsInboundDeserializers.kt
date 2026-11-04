package com.letta.mobile.data.transport.serialization

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


/**
 * Hand-written deserializer for the `subscribe_frame`
 * envelope (§11). The wrapper carries `run_id` + `seq` + an opaque `frame`
 * object whose shape is whatever BridgeFrame the shim recorded. We keep
 * the inner `frame` as a raw [JsonObject] so the caller can re-decode it
 * with [ServerFrameSerializer] and route it through the same handler as
 * a live frame — replayed and live frames must take the same code path.
 */
internal object SubscribeFrameDeserializer : KSerializer<ServerFrame> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ServerFrame.SubscribeFrameMessage")

    override fun deserialize(decoder: Decoder): ServerFrame {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("SubscribeFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val frameField = element["frame"]
        val innerFrame = (frameField as? JsonObject)
            ?: error("subscribe_frame envelope missing object-shaped 'frame' field")
        return ServerFrame.SubscribeFrameMessage(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            runId = element["run_id"]?.jsonPrimitive?.content
                ?: error("subscribe_frame envelope missing 'run_id'"),
            seq = element["seq"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: error("subscribe_frame envelope missing numeric 'seq'"),
            frame = innerFrame,
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.SubscribeFrameMessage is inbound-only; encoding it is never valid")
}

/**
 * Deserialize-only stand-in for the polymorphic strategy when the
 * envelope carries a `type` we don't recognize. Pulls the raw element
 * out of the [kotlinx.serialization.json.JsonDecoder] and packs it
 * into [ServerFrame.Unknown] without trying to map any individual
 * fields (we don't know the shape). Implements the full [KSerializer]
 * surface because [JsonContentPolymorphicSerializer.selectDeserializer]
 * casts its return value to KSerializer at runtime.
 */
internal object UnknownFrameDeserializer : KSerializer<ServerFrame> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ServerFrame.Unknown")

    override fun deserialize(decoder: Decoder): ServerFrame {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("UnknownFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        return ServerFrame.Unknown(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            type = element["type"]?.jsonPrimitive?.content.orEmpty(),
            raw = element,
        )
    }

    /**
     * We never need to serialize an [ServerFrame.Unknown] (it's
     * inbound-only — the client never produces unknown server
     * frames). Throwing here makes any accidental encode loud.
     */
    override fun serialize(
        encoder: Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.Unknown is inbound-only; encoding it is never valid")
}
