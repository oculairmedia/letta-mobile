package com.letta.mobile.data.model

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskUserQuestionTest {
    // Real captured shape (lester/agent-ca46df7f, 2026-07-25).
    private val captured = """
        {"questions":[{"question":"What should I ask you about?","header":"Test prompt","multiSelect":false,
        "options":[{"label":"Ping the transport","description":"Verify Iroh transport round-trip"},
        {"label":"Run a smoke test","description":"Quick connectivity check"},
        {"label":"Just exploring","description":"No specific task in mind"}]}]}
    """.trimIndent()

    @Test
    fun parseReadsQuestionsHeaderAndOptions() {
        val spec = AskUserQuestion.parse(captured)!!
        assertEquals(1, spec.questions.size)
        val q = spec.questions.first()
        assertEquals("What should I ask you about?", q.question)
        assertEquals("Test prompt", q.header)
        assertEquals(false, q.multiSelect)
        assertEquals(3, q.options.size)
        assertEquals("Ping the transport", q.options.first().label)
        assertEquals("Verify Iroh transport round-trip", q.options.first().description)
    }

    @Test
    fun parseHandlesDoubleEncodedArguments() {
        val doubleEncoded = Json.encodeToString(String.serializer(), captured)
        val spec = AskUserQuestion.parse(doubleEncoded)!!
        assertEquals("What should I ask you about?", spec.questions.first().question)
    }

    @Test
    fun parseReturnsNullForBlankOrQuestionless() {
        assertNull(AskUserQuestion.parse(null))
        assertNull(AskUserQuestion.parse(""))
        assertNull(AskUserQuestion.parse("""{"questions":[]}"""))
        assertNull(AskUserQuestion.parse("""{"questions":[{"question":""}]}"""))
    }

    @Test
    fun buildUpdatedInputEmbedsAnswersAndKeepsOriginalFields() {
        val updated = AskUserQuestion.buildUpdatedInput(
            captured,
            mapOf("What should I ask you about?" to listOf("Run a smoke test")),
        )
        // original questions preserved
        assertTrue(updated.containsKey("questions"))
        val answers = updated["answers"]!!.jsonObject
        assertEquals("Run a smoke test", answers["What should I ask you about?"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildUpdatedInputJoinsMultiSelectAnswers() {
        val updated = AskUserQuestion.buildUpdatedInput(
            """{"questions":[{"question":"Pick","multiSelect":true,"options":[]}]}""",
            mapOf("Pick" to listOf("A", "B")),
        )
        assertEquals("A, B", updated["answers"]!!.jsonObject["Pick"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodeAnswerReasonRoundTripsThroughDecodeAnswerReason() {
        val updated = AskUserQuestion.buildUpdatedInput(
            captured,
            mapOf("What should I ask you about?" to listOf("Run a smoke test")),
        )
        val encoded = AskUserQuestion.encodeAnswerReason(updated)
        val decoded = AskUserQuestion.decodeAnswerReason(encoded)
        assertEquals(updated, decoded)
    }

    @Test
    fun decodeAnswerReasonReturnsNullForOrdinaryReasons() {
        assertNull(AskUserQuestion.decodeAnswerReason(null))
        assertNull(AskUserQuestion.decodeAnswerReason("looks fine"))
        assertNull(AskUserQuestion.decodeAnswerReason(""))
    }

    @Test
    fun decodeAnswerReasonReturnsNullForMalformedEncodedPayload() {
        val truncated = AskUserQuestion.encodeAnswerReason(
            AskUserQuestion.buildUpdatedInput(captured, mapOf("q" to listOf("a"))),
        ).dropLast(10)
        assertNull(AskUserQuestion.decodeAnswerReason(truncated))
    }

    @Test
    fun encodeAnswerReasonEndToEndProducesAnswerForSelectedOption() {
        val updated = AskUserQuestion.buildUpdatedInput(
            captured,
            mapOf("What should I ask you about?" to listOf("Red")),
        )
        val decoded = AskUserQuestion.decodeAnswerReason(AskUserQuestion.encodeAnswerReason(updated))!!
        val answers = decoded["answers"]!!.jsonObject
        assertEquals("Red", answers["What should I ask you about?"]!!.jsonPrimitive.content)
    }
}
