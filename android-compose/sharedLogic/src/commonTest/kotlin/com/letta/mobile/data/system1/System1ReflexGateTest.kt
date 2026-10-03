package com.letta.mobile.data.system1

import com.letta.mobile.data.system1.System1ReflexGate.CompletionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the deterministic tier.
 *
 * These cover exactly the decisions the Laya checkpoint could not make: the
 * probe in `tools/system1-laya/calibration_probe.py` found overlapping
 * distributions for completeness (best min/max separation +0.007). The cases
 * below are the same drafts, and the gate separates all of them.
 */
class System1ReflexGateTest {

    private val gate = System1ReflexGate()

    private fun reason(draft: String) = gate.evaluate(draft).completionReason

    @Test
    fun terminal_punctuation_marks_a_draft_complete() {
        for (draft in listOf(
            "Can you write a Python function to sort a list?",
            "Please refactor the login handler.",
            "Stop!",
            "这是什么？",
        )) {
            assertEquals(CompletionReason.TERMINAL_PUNCTUATION, reason(draft), draft)
            assertTrue(gate.evaluate(draft).isComplete, draft)
        }
    }

    @Test
    fun a_draft_ending_on_a_dangling_word_is_incomplete() {
        // Every one of these is a draft the model scored as "complete".
        for (draft in listOf(
            "so i was thinking that maybe we could",
            "can you write a python function that",
            "what is the difference between a",
            "i need help with the",
            "how do i add a",
        )) {
            assertEquals(CompletionReason.DANGLING_WORD, reason(draft), draft)
            assertFalse(gate.evaluate(draft).isComplete, draft)
        }
    }

    @Test
    fun an_unpunctuated_but_settled_draft_is_complete() {
        val signals = gate.evaluate("run the failing test again")
        assertEquals(CompletionReason.SETTLED, signals.completionReason)
        assertTrue(signals.isComplete)
    }

    @Test
    fun a_very_short_unpunctuated_draft_is_not_complete() {
        assertEquals(CompletionReason.TOO_SHORT, reason("hello"))
        assertEquals(CompletionReason.TOO_SHORT, reason("run tests"))
        assertFalse(gate.evaluate("hello").isComplete)
    }

    @Test
    fun known_limitation_a_trailing_demonstrative_reads_as_settled() {
        // "explain what this" is unfinished, but "explain this" is a valid
        // request, so a last-token rule cannot separate them. Documented rather
        // than papered over: the cost is one wasted ~90ms call, not a wrong turn.
        assertEquals(CompletionReason.SETTLED, reason("explain what this"))
    }

    @Test
    fun an_empty_draft_is_empty_not_complete() {
        assertEquals(CompletionReason.EMPTY, reason(""))
        assertEquals(CompletionReason.EMPTY, reason("   \n "))
        assertFalse(gate.evaluate("").isComplete)
    }

    @Test
    fun completion_is_case_insensitive() {
        assertEquals(CompletionReason.DANGLING_WORD, reason("WHAT IS THE DIFFERENCE BETWEEN A"))
    }

    @Test
    fun trailing_whitespace_does_not_hide_terminal_punctuation() {
        assertEquals(CompletionReason.TERMINAL_PUNCTUATION, reason("is this done?   "))
    }

    @Test
    fun typing_one_more_character_is_not_a_substantive_change() {
        val signals = gate.evaluate(
            draft = "how do I add a migration",
            lastEvaluatedDraft = "how do I add a migratio",
        )
        assertEquals(0, signals.changedTokenCount)
        assertFalse(signals.isSubstantiveChange)
    }

    @Test
    fun adding_words_is_a_substantive_change() {
        val signals = gate.evaluate(
            draft = "how do I add a Room migration to the schema",
            lastEvaluatedDraft = "how do I add a Room migration",
        )
        assertTrue(signals.isSubstantiveChange)
        assertEquals(3, signals.changedTokenCount)
    }

    @Test
    fun reordering_words_counts_as_change_but_retyping_the_same_text_does_not() {
        val identical = gate.evaluate("run the tests", lastEvaluatedDraft = "run the tests")
        assertEquals(0, identical.changedTokenCount)
        assertFalse(identical.isSubstantiveChange)

        val reordered = gate.evaluate("tests the run now", lastEvaluatedDraft = "run the tests")
        assertTrue(reordered.changedTokenCount > 0)
    }

    @Test
    fun deleting_text_counts_as_change() {
        val signals = gate.evaluate("run", lastEvaluatedDraft = "run the failing tests again")
        assertEquals(4, signals.changedTokenCount)
        assertTrue(signals.isSubstantiveChange)
    }

    @Test
    fun the_first_evaluation_treats_the_whole_draft_as_changed() {
        val signals = gate.evaluate("write me a sort function", lastEvaluatedDraft = "")
        assertEquals(5, signals.changedTokenCount)
        assertTrue(signals.isSubstantiveChange)
    }

    @Test
    fun punctuation_does_not_inflate_the_token_delta() {
        val signals = gate.evaluate(
            draft = "is this done?",
            lastEvaluatedDraft = "is this done",
        )
        assertEquals(0, signals.changedTokenCount)
        assertFalse(signals.isSubstantiveChange)
    }

    @Test
    fun a_still_typing_user_is_not_idle() {
        val signals = gate.evaluate("write me a sort function.", msSinceLastEdit = 50)
        assertFalse(signals.isIdle)
        assertFalse(signals.shouldConsultSystem1)
    }

    @Test
    fun consulting_system1_requires_complete_changed_and_idle() {
        val ready = gate.evaluate(
            draft = "Can you write a Python function to sort a list?",
            lastEvaluatedDraft = "",
            msSinceLastEdit = 1_000,
        )
        assertTrue(ready.isComplete)
        assertTrue(ready.isSubstantiveChange)
        assertTrue(ready.isIdle)
        assertTrue(ready.shouldConsultSystem1)
    }

    @Test
    fun an_idle_but_unfinished_draft_does_not_consult_system1() {
        val signals = gate.evaluate(
            draft = "so i was thinking that maybe we could",
            lastEvaluatedDraft = "",
            msSinceLastEdit = 10_000,
        )
        assertTrue(signals.isIdle)
        assertFalse(signals.shouldConsultSystem1)
    }

    @Test
    fun an_unchanged_draft_does_not_re_consult_system1() {
        val draft = "Can you write a Python function to sort a list?"
        val signals = gate.evaluate(draft, lastEvaluatedDraft = draft, msSinceLastEdit = 10_000)

        assertTrue(signals.isComplete)
        assertFalse(signals.shouldConsultSystem1, "re-evaluating an unchanged draft wastes a call")
    }

    @Test
    fun config_thresholds_are_honoured() {
        val eager = System1ReflexGate(
            System1ReflexGate.Config(idleMsBeforeEvaluate = 0, minTokenDelta = 1),
        )
        val signals = eager.evaluate(
            draft = "run the failing tests",
            lastEvaluatedDraft = "run the failing",
            msSinceLastEdit = 0,
        )
        assertTrue(signals.shouldConsultSystem1)
    }
}
