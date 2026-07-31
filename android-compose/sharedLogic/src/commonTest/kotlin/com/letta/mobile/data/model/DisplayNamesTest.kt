package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun shortIdOfBareOrPrefixOnlyIdStaysUsable() {
        // Degenerate ids must not produce an empty label: a prefix-only id
        // falls back to the full original rather than "".
        assertEquals("agent-", DisplayNames.shortId("agent-"))
        assertEquals("x", DisplayNames.shortId("x"))
    }
}
