package com.letta.mobile.data.voice

import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointGrant
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceValuesTest {
    @Test
    fun spokenTextNeverPrintsItsWords() {
        val text = VoiceText("my secret plan")
        assertEquals("VoiceText(length=14)", text.toString())
        val action = VoiceActionDecision(VoiceActionKind("note"), mapOf("body" to "hidden"))
        val utterance = VoiceUtterance(text, VoiceText("ok"), VoiceDecision.Act(action))
        assertFalse("secret" in utterance.toString())
        assertFalse("hidden" in utterance.toString())
    }

    @Test
    fun levelsAreClamped() {
        assertEquals(AudioLevel(1f), AudioLevel.clamped(3f))
        assertEquals(AudioLevel.Silent, AudioLevel.clamped(Float.NaN))
    }

    @Test
    fun everyErrorCodeSuggestsAnAction() {
        assertEquals(VoiceUserAction.OPEN_SETTINGS, VoiceError(VoiceErrorCode.PERMISSION_REQUIRED, VoicePort.EAR).action)
        assertEquals(VoiceUserAction.NONE, VoiceErrorCode.CANCELLED.action)
        assertEquals(VoiceUserAction.RETRY, VoiceErrorCode.UNREACHABLE.action)
    }

    @Test
    fun defaultsKeepTheWakeWordOffAndGrantsSeparate() {
        val settings = VoiceSettings()
        assertFalse(settings.wakeWord.enabled)
        assertTrue(settings.bargeIn)
        assertFalse("grant" in settings.toString().lowercase())
    }

    @Test
    fun grantsNameOneOriginAndPayload() {
        val url = SpeechEndpointUrl("http://127.0.0.1:8880/")
        val grant = VoiceEndpointGrant(EndpointOrigin.of(url)!!, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS)
        val grants = VoiceEndpointGrants().with(grant)
        assertTrue(grants.allows(url, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
        assertFalse(grants.allows(url, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION))
        assertFalse(grants.allows(SpeechEndpointUrl("http://127.0.0.1:8881"), VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
        assertFalse(grants.without(grant).allows(url, VoiceEndpointPayload.TEXT_FOR_SYNTHESIS))
    }
}
