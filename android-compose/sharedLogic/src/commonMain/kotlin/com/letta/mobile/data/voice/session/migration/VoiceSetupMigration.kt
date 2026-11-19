package com.letta.mobile.data.voice.session.migration

import com.letta.mobile.data.rpcwire.SecretWriteValue
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.host.HostVoiceEndpointChoice
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceKeyChange
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.client.HostVoiceState
import com.letta.mobile.data.voice.host.client.SharedVoiceSetup
import com.letta.mobile.data.voice.session.VoiceSetup
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore

/** What the Voice settings may offer about this device's own speech server and the host's shared one. */
enum class VoiceShareOffer {
    /** Nothing to offer. */
    NONE,

    /** This device has its own server, the host has none, and this device is an admin: share it with every device. */
    SHARE,

    /** This device has its own server and the host has none, but only an admin device can share one. */
    ASK_ADMIN,

    /** The host's shared server is in force and this device still keeps its own: offer to remove it. */
    REMOVE_LOCAL,
}

/**
 * Moves this device's own speech server to the host so every device shares it, as the voice-endpoint
 * contract's migration describes: the address, model names, voice and this device's grants go in one
 * `voice.setup.set`; the key goes too only when the person explicitly asks ([share] with `sendKey`),
 * write-only, and is deleted here once the host's new status shows it holds one. The host wins once
 * it has a setup; this device's own setup is never deleted silently.
 */
class VoiceSetupMigration(
    private val store: VoiceSetupStore,
    private val shared: SharedVoiceSetup,
) {
    /** What to offer now. */
    val offer: VoiceShareOffer get() = shareOfferFor(store.setup.value, shared.state.value)

    /** Whether this device keeps a key that sharing could send. */
    val hasKey: Boolean get() = store.setup.value.hasApiKey

    /**
     * Writes this device's server to the host. With [sendKey] the stored key goes once inside the
     * write and is removed from this device after the host reports holding it; without, the host
     * gets no key. On success this device's audio acknowledgement for that origin is recorded when
     * the person had already allowed audio to it here. Null on success, else the host's code.
     */
    suspend fun share(sendKey: Boolean): HostVoiceErrorCode? {
        val local = store.setup.value
        val url = local.endpoint.baseUrl ?: return HostVoiceErrorCode.INVALID_REQUEST
        val key = if (sendKey) keyToSend(url) else HostVoiceKeyChange.Keep
        val grants = grantsFor(local, url)
        val command = HostVoiceSetupCommand(choiceFor(local, url), key, grants, shared.state.value.status?.revision)
        val status = shared.write(command) ?: return failure()
        if (key is HostVoiceKeyChange.Replace && status.hasApiKey) store.clearEndpoint(keyOnly = true)
        if (grants.audioForTranscription) store.acknowledgeHostAudio(EndpointOrigin.of(url))
        return null
    }

    /** This device's stored key, wrapped write-only for the one setup command that carries it; Keep without one. */
    private fun keyToSend(url: SpeechEndpointUrl): HostVoiceKeyChange =
        store.keyAfter(VoiceSetupDraft(url.value))?.let { HostVoiceKeyChange.Replace(SecretWriteValue(it)) } ?: HostVoiceKeyChange.Keep

    /** Why the write failed, as the host said. */
    private fun failure(): HostVoiceErrorCode = shared.state.value.error ?: HostVoiceErrorCode.INTERNAL

    /** Removes this device's own server (address, grants and key) once the host's shared one is in force. */
    fun removeLocal() = store.clearEndpoint()
}

/** The offer for [local] against what is known of the host ([host]). */
fun shareOfferFor(local: VoiceSetup, host: HostVoiceState): VoiceShareOffer = when {
    !knowsBoth(local, host) -> VoiceShareOffer.NONE
    host.configured -> if (local.deviceOnly) VoiceShareOffer.NONE else VoiceShareOffer.REMOVE_LOCAL
    else -> offerByAdmin(host.admin)
}

/** Whether there is anything to compare: a host serving the contract, its status read, and a server of this device's own. */
private fun knowsBoth(local: VoiceSetup, host: HostVoiceState): Boolean =
    host.supported && host.status != null && local.endpoint.baseUrl != null

/** What a host without a setup lets this device offer, by whether it is an admin (null: not known yet). */
private fun offerByAdmin(admin: Boolean?): VoiceShareOffer = when (admin) {
    true -> VoiceShareOffer.SHARE
    false -> VoiceShareOffer.ASK_ADMIN
    null -> VoiceShareOffer.NONE
}

/** The grants this device recorded for [url], as the shared grant record. */
private fun grantsFor(local: VoiceSetup, url: SpeechEndpointUrl): HostVoiceGrants = HostVoiceGrants(
    audioForTranscription = local.grants.allows(url, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION),
    textForSynthesis = local.grants.allows(url, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS),
)

/** This device's server at [url] with its model names, voice and language hint. */
private fun choiceFor(local: VoiceSetup, url: SpeechEndpointUrl): HostVoiceEndpointChoice {
    val endpoint = local.endpoint
    return HostVoiceEndpointChoice(url, endpoint.transcriptionModel, endpoint.speechModel, endpoint.voice, endpoint.language)
}
