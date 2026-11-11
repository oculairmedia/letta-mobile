package com.letta.mobile.plugin.keystore.wire

import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/**
 * A [SecretValue] as one standard padded base64 string. Internal on purpose: only the value-carrying
 * wire DTOs of this package (`keystore.get` results, `keystore.put` and `keystore.unlock` params)
 * may encode a secret, and the decoded byte array is zeroed once the value holds its own copy.
 */
internal object SecretValueWireSerializer : KSerializer<SecretValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.letta.mobile.plugin.keystore.wire.SecretValueBase64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SecretValue) {
        value.revealSecretBytes { bytes -> encoder.encodeString(Base64.encode(bytes)) }
    }

    override fun deserialize(decoder: Decoder): SecretValue {
        val bytes = Base64.decode(decoder.decodeString())
        try {
            return SecretValue.ofBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
