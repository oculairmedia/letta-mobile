package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.catalog.SpeechCatalog
import com.letta.mobile.data.voice.catalog.SpeechCatalogException
import com.letta.mobile.data.voice.catalog.SpeechCatalogSource
import com.letta.mobile.data.voice.host.HostVoiceEndpoint
import com.letta.mobile.data.voice.host.HostVoiceException
import com.letta.mobile.data.voice.host.HostVoiceListSource

/**
 * The shared speech server's catalog through the host (`voice.models.list`): the host asks the
 * server with the key it keeps, so this device never calls the server and never holds the key. Voices
 * the host only echoes from the stored setup (the server lists none) read as unlisted, so a picker
 * offers OpenAI's documented voices with the stored one. A host failure becomes its voice code.
 */
class HostSpeechCatalog(private val endpoint: HostVoiceEndpoint) : SpeechCatalogSource {
    override suspend fun load(): SpeechCatalog {
        val listed = try {
            endpoint.models()
        } catch (failure: HostVoiceException) {
            throw SpeechCatalogException(failure.code.voiceCode, failure)
        }
        val voices = listed.voices.map { it.id }.takeIf { listed.voicesSource == HostVoiceListSource.UPSTREAM }
        return SpeechCatalog(listed.models.map { it.id }, voices)
    }
}
