package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.model.provider.wire.SecretWriteValue
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.audio.pcm.WavParse
import com.letta.mobile.data.voice.host.HostTranscriptionParams
import com.letta.mobile.data.voice.host.HostVoiceEndpointChoice
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceKeyChange
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.session.VoiceEndpointRules
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** A speech server to check without storing it. Prints no key. */
internal class VoiceCandidate(val baseUrl: String, val apiKey: String?) {
    override fun toString(): String = "VoiceCandidate(keySet=${apiKey != null})"
}

/** One validated clip: its WAVE bytes and the language hint for this request. Prints no audio. */
internal class VoiceClip(val wav: ByteArray, val language: String?) {
    override fun toString(): String = "VoiceClip(bytes=${wav.size})"
}

/**
 * Decodes and validates voice request params. Every refusal is a [VoiceRefusal] with one closed code;
 * nothing here echoes a value, so a malformed key or clip never reaches an error or a log.
 */
internal object VoiceParams {
    /** `voice.setup.set` params as the setup command they carry. */
    fun setupCommand(params: JsonObject?): HostVoiceSetupCommand {
        val fields = params ?: invalid()
        requireContractVersion(fields)
        val baseUrl = requireAddress(fields.string("base_url"))
        return HostVoiceSetupCommand(
            endpoint = HostVoiceEndpointChoice(
                baseUrl = SpeechEndpointUrl(baseUrl),
                transcriptionModel = fields.name("transcription_model")?.let(::SpeechModelName),
                speechModel = fields.name("speech_model")?.let(::SpeechModelName),
                voice = fields.name("voice")?.let(::SpeechVoiceName),
                language = fields.name("language")?.let(::SpeechLanguage),
            ),
            apiKey = keyChange(fields),
            grants = grants(fields["grants"]),
            expectedRevision = fields.string("expected_revision"),
        )
    }

    /** At most one of `api_key` (store it) and `remove_api_key: true`; neither keeps the stored key. */
    private fun keyChange(fields: JsonObject): HostVoiceKeyChange {
        val key = fields.string("api_key")
        val remove = fields.flag("remove_api_key") == true
        return when {
            key == null -> if (remove) HostVoiceKeyChange.Remove else HostVoiceKeyChange.Keep
            remove || key.isBlank() -> invalid()
            else -> HostVoiceKeyChange.Replace(SecretWriteValue(key))
        }
    }

    /** `voice.setup.clear` params: the expected revision, if any. */
    fun clearRevision(params: JsonObject?): String? = params?.string("expected_revision")

    /** `voice.setup.test` params: a candidate, or null to check the stored setup. */
    fun candidate(params: JsonObject?): VoiceCandidate? {
        val fields = params ?: return null
        val baseUrl = fields.string("base_url")
        val key = fields.string("api_key")
        if (baseUrl == null) return if (key == null) null else invalid()
        if (key != null && key.isBlank()) invalid()
        return VoiceCandidate(requireAddress(baseUrl), key)
    }

    /**
     * `voice.transcribe` params as a clip: only `wav`, mono 16-bit PCM, at most 1 MiB decoded and 30 s by
     * its header. The size is checked before the clip is decoded.
     */
    fun clip(params: JsonObject?): VoiceClip {
        val fields = params ?: invalid()
        if ((fields.string("encoding") ?: HostTranscriptionParams.WAV) != HostTranscriptionParams.WAV) invalid()
        val bytes = clipBytes(fields.string("audio") ?: invalid())
        requireMonoWithinDuration(bytes)
        return VoiceClip(bytes, fields.name("language"))
    }

    /** The clip's bytes, refused as too large before and after decoding. */
    private fun clipBytes(encoded: String): ByteArray {
        if (encoded.length > MAX_AUDIO_BASE64_CHARS) refuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
        val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: invalid()
        if (bytes.size > HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES) refuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
        return bytes
    }

    /** Mono 16-bit PCM WAVE no longer than 30 s by its header. */
    private fun requireMonoWithinDuration(bytes: ByteArray) {
        val audio = (WavCodec.parse(bytes) as? WavParse.Decoded)?.audio ?: invalid()
        if (audio.format.channels != 1) invalid()
        val millis = audio.frames.toLong() * MILLIS_PER_SECOND / audio.format.sampleRateHz
        if (millis > HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_MS) refuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
    }

    /** `voice.speech` params: the text, at most 4096 characters. */
    fun speechText(params: JsonObject?): String {
        val text = params?.string("text")?.takeIf { it.isNotBlank() } ?: invalid()
        if (text.length > HostVoiceWire.MAX_SPEECH_TEXT_CHARS) refuse(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
        return text
    }

    /** A plain http(s) base URL, trimmed; anything else is `voice.invalid_request`. */
    private fun requireAddress(value: String?): String {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: invalid()
        if (VoiceEndpointRules.problem(trimmed, withKey = false) != null) invalid()
        return trimmed
    }

    private fun requireContractVersion(fields: JsonObject) {
        val version = fields["contract_version"] ?: return
        if ((version as? JsonPrimitive)?.intOrNull != HostVoiceWire.CONTRACT_VERSION) invalid()
    }

    private fun grants(element: JsonElement?): HostVoiceGrants {
        if (element == null || element == JsonNull) return HostVoiceGrants()
        val fields = element as? JsonObject ?: invalid()
        return HostVoiceGrants(
            audioForTranscription = fields.flag("audio_for_transcription") == true,
            textForSynthesis = fields.flag("text_for_synthesis") == true,
        )
    }

    /** A string field, null when absent or null; any other type is invalid. */
    private fun JsonObject.string(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else invalid()
        else -> invalid()
    }

    /** A model, voice or language name: trimmed, and absent when empty. */
    private fun JsonObject.name(key: String): String? = string(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.flag(key: String): Boolean? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) invalid() else value.booleanOrNull ?: invalid()
        else -> invalid()
    }

    private fun invalid(): Nothing = refuse(HostVoiceErrorCode.INVALID_REQUEST)

    private fun refuse(code: HostVoiceErrorCode): Nothing = throw VoiceRefusal(code)

    /** Base64 of the largest accepted clip, rounded up to whole groups. */
    private const val MAX_AUDIO_BASE64_CHARS = (HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES + 2) / 3 * 4
    private const val MILLIS_PER_SECOND = 1_000L
}
