package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.vad.Amplitude
import com.letta.mobile.data.voice.vad.Pitch
import com.letta.mobile.data.voice.vad.VadSensitivity
import com.letta.mobile.data.voice.vad.VadSignals
import com.letta.mobile.data.voice.vad.probabilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VadEarConfigTest {
    /** The loudest probability a config's detector gives a quiet voice (about -49 dBFS, just under the NORMAL minimum of -48) after a silent second. */
    private fun quietVoicePeak(config: VadEarConfig): Float {
        val signals = VadSignals()
        val detector = config.detectorFactory().create(config.format)
        detector.probabilities(signals.silence(1.seconds))
        return detector.probabilities(signals.tone(500.milliseconds, Pitch(220.0, Amplitude(160)))).max()
    }

    @Test
    fun aCopiedSensitivityReachesTheBuiltInDetector() {
        assertTrue(quietVoicePeak(VadEarConfig()) == 0f, "NORMAL must not hear -49 dBFS")
        assertTrue(quietVoicePeak(VadEarConfig().copy(sensitivity = VadSensitivity.HIGH)) >= 0.5f)
    }
}
