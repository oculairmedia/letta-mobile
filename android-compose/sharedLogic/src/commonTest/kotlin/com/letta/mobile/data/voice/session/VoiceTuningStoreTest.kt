package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The stored speech speed and end-of-turn silence. */
class VoiceTuningStoreTest {
    private val settings = InMemorySecureSettingsStore()
    private val store = VoiceSetupStore(settings, null)

    @Test
    fun defaultsAreNormalSpeedAndTheDefaultSilence() {
        assertEquals(VoiceTuning(SpeechSpeed.Normal, 900.milliseconds), store.setup.value.tuning)
        assertEquals(EndOfTurnPolicy.DEFAULT_TRAILING_SILENCE, EndOfTurnPolicy().trailingSilence)
    }

    @Test
    fun theSpeedIsStoredAndKeptAcrossStores() {
        store.setSpeechSpeed(SpeechSpeed(1.25f))
        assertEquals(SpeechSpeed(1.25f), store.setup.value.tuning.speechSpeed)
        assertEquals(SpeechSpeed(1.25f), VoiceSetupStore(settings, null).setup.value.tuning.speechSpeed)
    }

    @Test
    fun theSilenceIsStoredWithinItsBounds() {
        store.setEndOfTurnSilence(650.milliseconds)
        assertEquals(650.milliseconds, VoiceSetupStore(settings, null).setup.value.tuning.endOfTurnSilence)
        store.setEndOfTurnSilence(50.milliseconds)
        assertEquals(EndOfTurnPolicy.MIN_TRAILING_SILENCE, store.setup.value.tuning.endOfTurnSilence)
        store.setEndOfTurnSilence(10.seconds)
        assertEquals(EndOfTurnPolicy.MAX_TRAILING_SILENCE, store.setup.value.tuning.endOfTurnSilence)
    }

    @Test
    fun storedValuesOutOfRangeOrGarbledAreRepaired() {
        settings.putString(VoiceSetupStore.KEY_SPEECH_SPEED, "9")
        settings.putString(VoiceSetupStore.KEY_END_OF_TURN_SILENCE_MS, "soon")
        val tuning = VoiceSetupStore(settings, null).setup.value.tuning
        assertEquals(SpeechSpeed(SpeechSpeed.MAX), tuning.speechSpeed)
        assertEquals(EndOfTurnPolicy.DEFAULT_TRAILING_SILENCE, tuning.endOfTurnSilence)
    }

    @Test
    fun speedsOutsideTheOfferedRangeAreRefusedOrClamped() {
        assertFailsWith<IllegalArgumentException> { SpeechSpeed(2f) }
        assertEquals(SpeechSpeed(SpeechSpeed.MIN), SpeechSpeed.coerce(0.1f))
        assertEquals(SpeechSpeed.Normal, SpeechSpeed.coerce(Float.NaN))
    }
}
