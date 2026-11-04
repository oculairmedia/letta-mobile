package com.letta.mobile.data.transport.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Shared inbound-only JSON-object boundary for replay envelopes and unknown frames. */
internal class InboundJsonObjectDeserializer<T>(
    private val descriptorName: String,
    private val decoderName: String,
    private val decode: (JsonObject) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(descriptorName)

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder ?: error("$decoderName requires a JsonDecoder")
        return decode(jsonDecoder.decodeJsonElement().jsonObject)
    }

    override fun serialize(encoder: Encoder, value: T): Unit =
        error("$descriptorName is inbound-only; encoding it is never valid")
}
