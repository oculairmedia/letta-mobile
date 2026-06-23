package com.letta.mobile.data.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposerAutocompleteTest {
    @Test
    fun detectsSlashCommandAtStart() {
        val token = ComposerAutocomplete.activeToken("/sched")
        assertEquals(AutocompleteTrigger.Command, token?.trigger)
        assertEquals("sched", token?.query)
    }

    @Test
    fun slashCommandStopsAfterWhitespace() {
        // Once the command token is complete (space typed), it is no longer active.
        assertNull(ComposerAutocomplete.activeToken("/sched now"))
    }

    @Test
    fun midSentenceSlashIsNotACommand() {
        assertNull(ComposerAutocomplete.activeToken("run cd /opt"))
    }

    @Test
    fun detectsMentionAtCursor() {
        val token = ComposerAutocomplete.activeToken("look at @Usa")
        assertEquals(AutocompleteTrigger.Mention, token?.trigger)
        assertEquals("Usa", token?.query)
        assertEquals(8, token?.start)
    }

    @Test
    fun emailLikeAtIsNotAMention() {
        // '@' must be at a word boundary, so "me@host" doesn't trigger.
        assertNull(ComposerAutocomplete.activeToken("ping me@host"))
    }

    @Test
    fun mentionEndsAtWhitespace() {
        assertNull(ComposerAutocomplete.activeToken("@Atlas done"))
    }

    @Test
    fun replaceTokenSwapsTheSpan() {
        val text = "look at @Usa"
        val token = ComposerAutocomplete.activeToken(text)!!
        val result = ComposerAutocomplete.replaceToken(text, token, "@UsageScreen.kt ")
        assertEquals("look at @UsageScreen.kt ", result)
    }

    @Test
    fun noTriggerReturnsNull() {
        assertNull(ComposerAutocomplete.activeToken("just a normal message"))
    }
}
