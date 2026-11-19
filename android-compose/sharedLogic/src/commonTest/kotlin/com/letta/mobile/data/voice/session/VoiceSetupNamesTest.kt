package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The model and voice names chosen for this device's own speech server: stored, kept, reset and cleared. */
class VoiceSetupNamesTest {
    private val settings = InMemorySecureSettingsStore()
    private val store = VoiceSetupStore(settings, InMemorySecureSettingsStore())
    private val url = "https://speech.example.invalid"

    @Test
    fun namesDefaultUntilChosenAndAreStoredBySave() {
        assertEquals(SpeechModelName("whisper-1"), store.setup.value.endpoint.transcriptionModel)
        assertNull(store.save(VoiceSetupDraft(url, transcriptionModel = "stt-model-a", speechModel = "tts-model-a", voice = " voice-a ")))
        val endpoint = store.setup.value.endpoint
        assertEquals(SpeechModelName("stt-model-a"), endpoint.transcriptionModel)
        assertEquals(SpeechModelName("tts-model-a"), endpoint.speechModel)
        assertEquals(SpeechVoiceName("voice-a"), endpoint.voice)
    }

    @Test
    fun anUnchangedNameIsKeptAndABlankOneReturnsToTheDefault() {
        assertNull(store.save(VoiceSetupDraft(url, speechModel = "tts-model-a", voice = "voice-a")))
        assertNull(store.save(VoiceSetupDraft(url, voice = "")))
        assertEquals(SpeechModelName("tts-model-a"), store.setup.value.endpoint.speechModel)
        assertEquals(SpeechVoiceName("alloy"), store.setup.value.endpoint.voice)
    }

    @Test
    fun removingTheServerForgetsItsNamesButMovingTheKeyKeepsThem() {
        assertNull(store.save(VoiceSetupDraft(url, apiKey = "sk-placeholder", speechModel = "tts-model-a")))
        store.clearEndpoint(keyOnly = true)
        assertEquals(SpeechModelName("tts-model-a"), store.setup.value.endpoint.speechModel)
        store.clearEndpoint()
        assertEquals(SpeechModelName("tts-1"), store.setup.value.endpoint.speechModel)
        assertNull(settings.getString(VoiceSetupStore.KEY_SPEECH_MODEL))
    }
}
