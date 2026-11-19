package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.SpeechDetectorFactory
import com.letta.mobile.data.voice.vad.TurnEndReason
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** A closed turn reports how long ago the person stopped speaking, measured in audio time. */
class TrailingSilenceReportTest {
    @Test
    fun aTurnClosedBySilenceReportsTheSilenceItWaitedOut() = runTest {
        val closed = mutableListOf<Pair<EarTurnReport, TurnEndReason>>()
        val observer = object : EarObserver {
            override fun turnClosed(report: EarTurnReport, reason: TurnEndReason) {
                closed += report to reason
            }
        }
        val policy = EndOfTurnPolicy(trailingSilence = 640.milliseconds)
        val config = VadEarConfig(policy = policy, detectors = SpeechDetectorFactory { ValueDetector() }, observer = observer)
        val ear = VadVoiceEar(ScriptedSource(turn()), ScriptedTranscriber(heard("hello")), config)

        ear.listen().takeWhile { it !is VoiceEarEvent.EndOfTurn }.toList()

        val (report, reason) = closed.single()
        assertEquals(TurnEndReason.SILENCE, reason)
        assertEquals(640.milliseconds, report.trailingSilence, "twenty 32 ms frames of silence after the last voice")
    }
}
