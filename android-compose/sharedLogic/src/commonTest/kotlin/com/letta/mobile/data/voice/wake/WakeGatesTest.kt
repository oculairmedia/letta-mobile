package com.letta.mobile.data.voice.wake

import com.letta.mobile.data.voice.WakePhrase
import com.letta.mobile.data.voice.WakePhraseProblem
import com.letta.mobile.data.voice.WakeSensitivity
import com.letta.mobile.data.voice.audio.SamplePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WakeGatesTest {
    @Test
    fun cooldownAdmitsOncePerWindow() {
        val gate = CooldownGate(2.seconds)
        val admitted = listOf(0.seconds, 1.seconds, 1999.milliseconds, 2.seconds, 3.seconds, 4.5.seconds).map(gate::admit)
        assertEquals(listOf(true, false, false, true, false, true), admitted)
    }

    @Test
    fun suppressionCoversSpeechAndItsTail() {
        val gate = OutputSuppressionGate(500.milliseconds)
        val timeline: List<Pair<Pair<Boolean, Duration>, Boolean>> = listOf(
            (false to 0.seconds) to false,
            (true to 1.seconds) to true,
            (false to 2.seconds) to true,
            (false to 2400.milliseconds) to true,
            (false to 2500.milliseconds) to false,
        )
        timeline.forEach { (input, expected) -> assertEquals(expected, gate.suppressed(input.first, input.second), "at ${input.second}") }
    }

    @Test
    fun sensitivityGateComparesConfidenceToThreshold() {
        val cases = listOf(0.4f to 0.5f to false, 0.5f to 0.5f to true, 0.2f to 0.9f to true)
        cases.forEach { (setup, expected) ->
            val phrase = WakePhrase("hey letta", sensitivity = WakeSensitivity(setup.second))
            val hit = WakeWordHit(phrase, DetectionConfidence(setup.first), SamplePosition(0))
            assertEquals(expected, SensitivityGate.passes(hit), "confidence ${setup.first}")
        }
    }

    @Test
    fun phraseValidationIsTableDriven() {
        val cases = mapOf(
            "hey letta" to null,
            "   " to WakePhraseProblem.EMPTY,
            "hi" to WakePhraseProblem.TOO_SHORT,
            "a".repeat(WakePhrase.MAX_LENGTH + 1) to WakePhraseProblem.TOO_LONG,
            "hey‮letta" to WakePhraseProblem.FORBIDDEN_CHARACTER,
            "hey\u0007letta" to WakePhraseProblem.FORBIDDEN_CHARACTER,
        )
        cases.forEach { (text, problem) -> assertEquals(problem, WakePhrase.problemWith(text), text) }
    }
}
