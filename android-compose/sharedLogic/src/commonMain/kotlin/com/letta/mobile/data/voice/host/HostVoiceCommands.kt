package com.letta.mobile.data.voice.host

import com.letta.mobile.data.rpcwire.SecretWriteValue
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The speech server and the names a shared setup uses on it. */
data class HostVoiceEndpointChoice(
    /** The server's base URL, without `/v1`; plain http(s), no user info, query or fragment. */
    val baseUrl: SpeechEndpointUrl,
    /** The transcription model; null leaves listening through the host unavailable. */
    val transcriptionModel: SpeechModelName? = null,
    /** The speech model; null leaves speaking through the host unavailable. */
    val speechModel: SpeechModelName? = null,
    /** The voice; null leaves speaking through the host unavailable. */
    val voice: SpeechVoiceName? = null,
    /** A language hint for transcription; null lets the server detect it. */
    val language: SpeechLanguage? = null,
)

/** What a setup write does to the stored key. */
sealed interface HostVoiceKeyChange {
    /** Keep the stored key, but only while the origin is unchanged: a new origin never inherits it. */
    data object Keep : HostVoiceKeyChange

    /** Delete the stored key. */
    data object Remove : HostVoiceKeyChange

    /** Store [key] in place of any stored one. Printing it shows no part of the key. */
    class Replace(
        /** The new key; read only to encode the write. */
        val key: SecretWriteValue,
    ) : HostVoiceKeyChange {
        override fun toString(): String = "Replace($key)"
    }
}

/**
 * Write-only command that stores the shared setup on the host. It is not a data class and its
 * serializer cannot decode, so the key it may carry has no equality, copy or read-back path.
 */
@Serializable(with = HostVoiceSetupCommandSerializer::class)
class HostVoiceSetupCommand(
    /** The server and names. */
    val endpoint: HostVoiceEndpointChoice,
    /** What to do with the stored key. */
    val apiKey: HostVoiceKeyChange = HostVoiceKeyChange.Keep,
    /** The payloads the person allows to go to the server's origin. */
    val grants: HostVoiceGrants = HostVoiceGrants(),
    /** The revision this write replaces; null writes regardless of concurrent changes. */
    val expectedRevision: String? = null,
) {
    override fun toString(): String =
        "HostVoiceSetupCommand(apiKey=$apiKey, grants=$grants, expectedRevision=$expectedRevision)"
}

/**
 * A speech server to check without storing it. Without [apiKey], the host sends its stored key only
 * when [baseUrl] has the stored setup's origin, so a stored key never reaches another server.
 */
@Serializable(with = HostVoiceConnectionCandidateSerializer::class)
class HostVoiceConnectionCandidate(
    /** The server's base URL. */
    val baseUrl: SpeechEndpointUrl,
    /** A key to check with; never stored. */
    val apiKey: SecretWriteValue? = null,
) {
    override fun toString(): String = "HostVoiceConnectionCandidate(keySet=${apiKey != null})"
}

/** `voice.setup.clear` params. */
@Serializable
internal data class HostVoiceClearParams(
    @SerialName("expected_revision") val expectedRevision: String? = null,
)

/** Encodes [HostVoiceSetupCommand] as `voice.setup.set` params; refuses to decode one. */
object HostVoiceSetupCommandSerializer : KSerializer<HostVoiceSetupCommand> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HostVoiceSetupCommand")

    override fun serialize(encoder: Encoder, value: HostVoiceSetupCommand) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Voice setup commands are JSON only")
        json.encodeJsonElement(setupParams(value))
    }

    override fun deserialize(decoder: Decoder): HostVoiceSetupCommand =
        throw SerializationException("Voice setup commands are write-only")

    private fun setupParams(value: HostVoiceSetupCommand): JsonObject = buildJsonObject {
        put("contract_version", HostVoiceWire.CONTRACT_VERSION)
        endpointFields(value.endpoint)
        when (val key = value.apiKey) {
            HostVoiceKeyChange.Keep -> Unit
            HostVoiceKeyChange.Remove -> put("remove_api_key", true)
            is HostVoiceKeyChange.Replace -> put("api_key", key.key.revealForEncoding())
        }
        put(
            "grants",
            buildJsonObject {
                put("audio_for_transcription", value.grants.audioForTranscription)
                put("text_for_synthesis", value.grants.textForSynthesis)
            },
        )
        value.expectedRevision?.let { put("expected_revision", it) }
    }

    private fun JsonObjectBuilder.endpointFields(endpoint: HostVoiceEndpointChoice) {
        put("base_url", endpoint.baseUrl.value)
        endpoint.transcriptionModel?.let { put("transcription_model", it.value) }
        endpoint.speechModel?.let { put("speech_model", it.value) }
        endpoint.voice?.let { put("voice", it.value) }
        endpoint.language?.let { put("language", it.value) }
    }
}

/** Encodes [HostVoiceConnectionCandidate] as `voice.setup.test` params; refuses to decode one. */
object HostVoiceConnectionCandidateSerializer : KSerializer<HostVoiceConnectionCandidate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HostVoiceConnectionCandidate")

    override fun serialize(encoder: Encoder, value: HostVoiceConnectionCandidate) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Voice connection checks are JSON only")
        json.encodeJsonElement(
            buildJsonObject {
                put("base_url", value.baseUrl.value)
                value.apiKey?.let { put("api_key", it.revealForEncoding()) }
            },
        )
    }

    override fun deserialize(decoder: Decoder): HostVoiceConnectionCandidate =
        throw SerializationException("Voice connection checks are write-only")
}
