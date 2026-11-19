package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText
import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentBatchingTest {
    private fun batch(vararg segments: String, firstAlone: Boolean, max: Int = 240): List<String> =
        batchSegments(segments.map(::VoiceText), firstAlone, max).map { it.value }

    @Test
    fun theFirstSegmentOfAReplyGoesAloneAndTheRestJoin() {
        assertEquals(
            listOf("It is noon.", "It is sunny. It is warm."),
            batch("It is noon.", "It is sunny.", "It is warm.", firstAlone = true),
        )
    }

    @Test
    fun aLoneFirstSegmentIsNeverHeldOrJoined() {
        assertEquals(listOf("It is noon."), batch("It is noon.", firstAlone = true))
    }

    @Test
    fun laterSegmentsReleasedTogetherJoin() {
        assertEquals(listOf("It is sunny. It is warm."), batch("It is sunny.", "It is warm.", firstAlone = false))
    }

    @Test
    fun aBatchNeverOutgrowsTheSegmentLimit() {
        val sentence = "Twenty characters ok."
        val batches = batch(sentence, sentence, sentence, sentence, firstAlone = false, max = 50)
        assertEquals(listOf("$sentence $sentence", "$sentence $sentence"), batches)
        assertEquals(true, batches.all { it.length <= 50 })
    }

    @Test
    fun nothingReleasedMeansNothingToSpeak() {
        assertEquals(emptyList(), batch(firstAlone = true))
        assertEquals(emptyList(), batch(firstAlone = false))
    }
}
