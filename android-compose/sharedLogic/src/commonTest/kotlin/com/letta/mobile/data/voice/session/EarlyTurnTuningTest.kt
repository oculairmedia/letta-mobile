package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.ear.EarlyTurns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The early-turn pause: off unless the person stores one, kept within its range, and survives a restart. */
class EarlyTurnTuningTest {
    private val settings = InMemorySecureSettingsStore()
    private val store = VoiceSetupStore(settings, InMemorySecureSettingsStore())

    @Test
    fun earlyTurnsAreOffUntilAPauseIsStored() {
        assertNull(store.setup.value.tuning.earlyTurnPause)
        store.setEarlyTurnPause(EarlyTurns.DEFAULT_PAUSE)
        assertEquals(350.milliseconds, VoiceSetupStore(settings, null).setup.value.tuning.earlyTurnPause)
        store.setEarlyTurnPause(null)
        assertNull(VoiceSetupStore(settings, null).setup.value.tuning.earlyTurnPause)
    }

    @Test
    fun pausesOutsideTheRangeAreClampedAndGarbleIsIgnored() {
        store.setEarlyTurnPause(10.milliseconds)
        assertEquals(EarlyTurns.MIN_PAUSE, store.setup.value.tuning.earlyTurnPause)
        store.setEarlyTurnPause(9.seconds)
        assertEquals(EndOfTurnPolicy.MAX_TRAILING_SILENCE, store.setup.value.tuning.earlyTurnPause)
        settings.putString(VoiceSetupStore.KEY_EARLY_TURN_PAUSE_MS, "soon")
        assertNull(VoiceSetupStore(settings, null).setup.value.tuning.earlyTurnPause)
    }
}
