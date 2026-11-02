package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A missing current value differs from a present JSON null, represented by JsonNull. */
@Serializable(with = CompareAndSetMetadataResponseSerializer::class)
data class CompareAndSetMetadataResponse(
    val swapped: Boolean,
    val current: JsonElement? = null,
)

private object CompareAndSetMetadataResponseSerializer : KSerializer<CompareAndSetMetadataResponse> {
    override val descriptor = buildClassSerialDescriptor("CompareAndSetMetadataResponse") {
        element<Boolean>("swapped")
        element<JsonElement>("current", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): CompareAndSetMetadataResponse {
        val obj = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return CompareAndSetMetadataResponse(obj.getValue("swapped").jsonPrimitive.boolean, obj["current"])
    }

    override fun serialize(encoder: Encoder, value: CompareAndSetMetadataResponse) {
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject {
            put("swapped", JsonPrimitive(value.swapped))
            value.current?.let { put("current", it) }
        })
    }
}
