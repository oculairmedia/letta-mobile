package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSearchMatcherTest {

    private val pmLettaMobile = Agent(
        id = AgentId("agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"),
        name = "PM-letta-mobile",
        description = "Project manager for the mobile client",
        model = "anthropic/claude-opus-4-8",
        tags = listOf("origin:letta-code", "pm"),
    )

    private val meridian = Agent(
        id = AgentId("agent-597b5756-2915-4560-ba6b-91005f085166"),
        name = "Meridian",
        description = "Part mirror, part coach",
        model = "anthropic/claude-opus-4-8",
    )

    /** The reported repro: reordered words never matched the substring filter. */
    @Test
    fun matchesTokensInAnyOrder() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "letta-mobile pm"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "pm letta-mobile"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "mobile pm"))
    }

    /** Separator mismatch was the other half of the repro. */
    @Test
    fun matchesAcrossWordSeparators() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "letta mobile"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "pm letta mobile"))
    }

    @Test
    fun matchesWholeNameSubstringStill() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "PM-letta-mobile"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "letta-mob"))
    }

    @Test
    fun matchesIsCaseInsensitive() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "PM LETTA"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "pM lEtTa"))
    }

    /** Backend `query_text` covers the id; the local surfaces did not. */
    @Test
    fun matchesAgentId() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "c356b54a"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"))
    }

    @Test
    fun matchesDescriptionModelAndTags() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "project manager"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "opus"))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "origin:letta-code"))
    }

    /** Token-AND, not token-OR: an unmatched token must exclude the agent. */
    @Test
    fun requiresEveryTokenToMatch() {
        assertFalse(AgentSearchMatcher.matches(pmLettaMobile, "pm desktop"))
        assertFalse(AgentSearchMatcher.matches(meridian, "meridian mobile"))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, ""))
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "   "))
    }

    @Test
    fun filterPreservesInputOrderAndBlankQuery() {
        val agents = listOf(pmLettaMobile, meridian)
        assertEquals(agents, AgentSearchMatcher.filter(agents, "  "))
        assertEquals(listOf(pmLettaMobile), AgentSearchMatcher.filter(agents, "letta-mobile pm"))
        assertEquals(listOf(meridian), AgentSearchMatcher.filter(agents, "meridian"))
    }

    @Test
    fun collapsesRepeatedWhitespaceBetweenTokens() {
        assertTrue(AgentSearchMatcher.matches(pmLettaMobile, "  pm    mobile  "))
    }

    @Test
    fun matchesAgentSummaryProjection() {
        val summary = AgentSummary(id = pmLettaMobile.id, name = pmLettaMobile.name)
        assertTrue(AgentSearchMatcher.matches(summary, "letta-mobile pm"))
        assertFalse(AgentSearchMatcher.matches(summary, "nonexistent"))
    }
}
