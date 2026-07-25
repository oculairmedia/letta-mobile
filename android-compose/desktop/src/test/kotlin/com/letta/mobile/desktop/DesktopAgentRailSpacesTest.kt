package com.letta.mobile.desktop

import com.letta.mobile.data.agents.AgentRailGroup
import com.letta.mobile.data.agents.RAIL_CATCH_ALL_SPACE
import com.letta.mobile.data.agents.deriveAgentSpaces
import com.letta.mobile.data.chat.runtime.NowActiveStatus
import com.letta.mobile.data.chat.runtime.nowActiveStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAgentRailSpacesTest {
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
    fun nowActiveStatusPrecedence() {
        assertEquals(NowActiveStatus.Error, nowActiveStatus(isThinking = true, isStreaming = true, hasError = true))
        assertEquals(NowActiveStatus.Thinking, nowActiveStatus(isThinking = true, isStreaming = false, hasError = false))
        assertEquals(NowActiveStatus.Streaming, nowActiveStatus(isThinking = false, isStreaming = true, hasError = false))
        assertEquals(NowActiveStatus.Idle, nowActiveStatus(isThinking = false, isStreaming = false, hasError = false))
    }
}
