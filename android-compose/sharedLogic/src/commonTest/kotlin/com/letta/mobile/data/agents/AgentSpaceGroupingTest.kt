package com.letta.mobile.data.agents

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentSpaceGroupingTest {
    private fun group(name: String, vararg ids: String) = AgentRailGroup(name, ids.toList())

    @Test
    fun sharedPrefixesBecomeSpacesRestFallsThrough() {
        val spaces = deriveAgentSpaces(
            listOf(
                group("PM - social-hause", "a"),
                group("Meridian", "b"),
                group("PM - vibesync", "c"),
                group("lester", "d"),
            ),
        )
        assertEquals(listOf("PM", RAIL_CATCH_ALL_SPACE), spaces.map { it.name })
        assertEquals(listOf("PM - social-hause", "PM - vibesync"), spaces[0].groups.map { it.name })
        assertEquals(listOf("Meridian", "lester"), spaces[1].groups.map { it.name })
    }

    @Test
    fun singletonPrefixStaysInCatchAll() {
        val spaces = deriveAgentSpaces(
            listOf(group("QA - only-one", "a"), group("Meridian", "b")),
        )
        assertEquals(listOf(RAIL_CATCH_ALL_SPACE), spaces.map { it.name })
    }

    @Test
    fun spaceAggregatesStackedAgentCounts() {
        val spaces = deriveAgentSpaces(
            listOf(group("PM - a", "1", "2", "3"), group("PM - b", "4")),
        )
        assertEquals(4, spaces.single().agentCount)
    }

    @Test
    fun preservesFirstAppearanceOrderAcrossSpaces() {
        val spaces = deriveAgentSpaces(
            listOf(group("Meridian", "a"), group("PM - x", "b"), group("PM - y", "c")),
        )
        // "Agents" first because Meridian appeared first.
        assertEquals(listOf(RAIL_CATCH_ALL_SPACE, "PM"), spaces.map { it.name })
    }

    @Test
    fun emptyRosterProducesNoSpaces() {
        assertEquals(emptyList(), deriveAgentSpaces(emptyList()))
    }
}
