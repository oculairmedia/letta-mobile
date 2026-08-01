package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayNamesTest {

    @Test
    fun realNamePassesThrough() {
        assertEquals("PM - letta-mobile", DisplayNames.agent("PM - letta-mobile", "agent-c356b8f2"))
        assertEquals("Meridian", DisplayNames.agent("  Meridian  ", "agent-1"))
    }

    @Test
    fun blankOrMissingNameFallsBackToShortId() {
        // The defect this exists for: headers rendered "agent-c356b8f2-…" raw.
        assertEquals("Agent c356b8f2", DisplayNames.agent(null, "agent-c356b8f2-1a2b-3c4d"))
        assertEquals("Agent c356b8f2", DisplayNames.agent("", "agent-c356b8f2-1a2b-3c4d"))
        assertEquals("Agent c356b8f2", DisplayNames.agent("   ", "agent-c356b8f2-1a2b-3c4d"))
    }

    @Test
    fun nameEqualToIdCountsAsUnresolved() {
        // Upstream code often pre-falls-back name := id; that must not leak.
        assertEquals("Agent c356b8f2", DisplayNames.agent("agent-c356b8f2-1a2b", "agent-c356b8f2-1a2b"))
    }

    @Test
    fun shortIdStripsStackedTypePrefixes() {
        assertEquals("3b4633ef", DisplayNames.shortId("agent-local-3b4633ef-e604-4492"))
        assertEquals("aa20596c", DisplayNames.shortId("conv-aa20596c-7af1"))
        assertEquals("deadbeef", DisplayNames.shortId("deadbeef-0000"))
    }

    @Test
    fun collidingFallbackLabelsAreWidenedUntilDistinct() {
        // The defect: ids are prefixed UUIDs, so the first eight characters
        // after the prefix are NOT unique. Two unresolved agents rendered the
        // same "Agent 12345678", and the rail stacks by display name — the
        // second agent became unreachable.
        val widened = DisplayNames.disambiguateAgentFallbacks(
            listOf(
                "agent-12345678-aaaa-1111" to DisplayNames.agent(null, "agent-12345678-aaaa-1111"),
                "agent-12345678-bbbb-2222" to DisplayNames.agent(null, "agent-12345678-bbbb-2222"),
            ),
        )
        assertEquals(2, widened.map { it.second }.distinct().size, "colliding labels must end up distinct")
        widened.forEach { (_, label) -> assertTrue(DisplayNames.isAgentFallback(label)) }
    }

    @Test
    fun realNamesAreNeverDisambiguated() {
        // Two agents genuinely called the same thing IS a fact about them, and
        // the rail deliberately stacks them. Only synthetic labels get widened.
        val agents = listOf("agent-1" to "Letta Code", "agent-2" to "Letta Code")
        assertEquals(agents, DisplayNames.disambiguateAgentFallbacks(agents))
    }

    @Test
    fun distinctFallbacksAreLeftAlone() {
        val agents = listOf(
            "agent-aaaaaaaa-1" to DisplayNames.agent(null, "agent-aaaaaaaa-1"),
            "agent-bbbbbbbb-2" to DisplayNames.agent(null, "agent-bbbbbbbb-2"),
        )
        assertEquals(agents, DisplayNames.disambiguateAgentFallbacks(agents))
    }

    @Test
    fun onlySynthesisedLabelsReportAsFallbacks() {
        assertTrue(DisplayNames.isAgentFallback(DisplayNames.agent(null, "agent-c356b8f2")))
        assertFalse(DisplayNames.isAgentFallback("Meridian"))
        // The prefix includes its trailing space, so a real name that merely
        // starts with the word "Agent" stays on the right side of this.
        assertFalse(DisplayNames.isAgentFallback("Agent"))
    }

    @Test
    fun shortIdOfBareOrPrefixOnlyIdStaysUsable() {
        // Degenerate ids must not produce an empty label: a prefix-only id
        // falls back to the full original rather than "".
        assertEquals("agent-", DisplayNames.shortId("agent-"))
        assertEquals("x", DisplayNames.shortId("x"))
    }
}
