package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.model.provider.wire.SecretWriteValue
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.endpoint.TranscriptionPurpose
import com.letta.mobile.data.voice.host.HostVoiceEndpointChoice
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceKeyChange
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.session.VoiceEndpointRules
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A speech server to check without storing it. Prints no key. */
internal class VoiceCandidate(val baseUrl: String, val apiKey: String?) {
    override fun toString(): String = "VoiceCandidate(keySet=${apiKey != null})"
}

/** One validated clip: its WAVE bytes, the language hint for this request and what it is for. Prints no audio. */
internal class VoiceClip(
    val wav: ByteArray,
    val language: String?,
    val purpose: TranscriptionPurpose = TranscriptionPurpose.TURN,
) {
    override fun toString(): String = "VoiceClip(bytes=${wav.size}, purpose=$purpose)"
}

/**
 * Decodes and validates the setup methods' params. Every refusal is a [VoiceRefusal] with one closed code;
 * nothing here echoes a value, so a malformed key or clip never reaches an error or a log.
 */
internal object VoiceParams {
    /** `voice.setup.set` params as the setup command they carry. */
    fun setupCommand(params: JsonObject?): HostVoiceSetupCommand {
        val fields = params ?: voiceInvalid()
        requireContractVersion(fields)
        val baseUrl = requireAddress(fields.voiceString("base_url"))
        return HostVoiceSetupCommand(
            endpoint = HostVoiceEndpointChoice(
                baseUrl = SpeechEndpointUrl(baseUrl),
                transcriptionModel = fields.voiceName("transcription_model")?.let(::SpeechModelName),
                speechModel = fields.voiceName("speech_model")?.let(::SpeechModelName),
                voice = fields.voiceName("voice")?.let(::SpeechVoiceName),
                language = fields.voiceName("language")?.let(::SpeechLanguage),
            ),
            apiKey = keyChange(fields),
            grants = grants(fields["grants"]),
            expectedRevision = fields.voiceString("expected_revision"),
        )
    }

    /** At most one of `api_key` (store it) and `remove_api_key: true`; neither keeps the stored key. */
    private fun keyChange(fields: JsonObject): HostVoiceKeyChange {
        val key = fields.voiceString("api_key")
        val remove = fields.voiceFlag("remove_api_key") == true
        return when {
            key == null -> if (remove) HostVoiceKeyChange.Remove else HostVoiceKeyChange.Keep
            remove || key.isBlank() -> voiceInvalid()
            else -> HostVoiceKeyChange.Replace(SecretWriteValue(key))
        }
    }

    /** `voice.setup.clear` params: the expected revision, if any. */
    fun clearRevision(params: JsonObject?): String? = params?.voiceString("expected_revision")

    /** `voice.setup.test` params: a candidate, or null to check the stored setup. */
    fun candidate(params: JsonObject?): VoiceCandidate? {
        val fields = params ?: return null
        val baseUrl = fields.voiceString("base_url")
        val key = fields.voiceString("api_key")
        if (baseUrl == null) return if (key == null) null else voiceInvalid()
        if (key != null && key.isBlank()) voiceInvalid()
        return VoiceCandidate(requireAddress(baseUrl), key)
    }

    /** A plain http(s) base URL, trimmed; anything else is `voice.invalid_request`. */
    private fun requireAddress(value: String?): String {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: voiceInvalid()
        if (VoiceEndpointRules.problem(trimmed, withKey = false) != null) voiceInvalid()
        return trimmed
    }

    private fun requireContractVersion(fields: JsonObject) {
        val version = fields["contract_version"] ?: return
        if ((version as? JsonPrimitive)?.intOrNull != HostVoiceWire.CONTRACT_VERSION) voiceInvalid()
    }

    private fun grants(element: JsonElement?): HostVoiceGrants {
        if (element == null || element == JsonNull) return HostVoiceGrants()
        val fields = element as? JsonObject ?: voiceInvalid()
        return HostVoiceGrants(
            audioForTranscription = fields.voiceFlag("audio_for_transcription") == true,
            textForSynthesis = fields.voiceFlag("text_for_synthesis") == true,
        )
    }

}
