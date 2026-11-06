@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.rail

import com.letta.mobile.data.objectverbs.binding.withDevicePins

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.agents.AgentRailGroup
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.data.objectverbs.ObjectFacets
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.binding.DevicePins
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.10: the expanded rail's agent library is a roster of spaces, pinned agents first. */
class ShellAgentLibraryTest {
    private val agents = listOf("a1" to "PM - letta-mobile", "a2" to "PM - letta-web", "a3" to "Scout", "a4" to "Meridian")

    @Test
    fun missingScopedPinsStayVisibleInOrderUntilRosterLoads() = runComposeUiTest {
        val source = MutableStateFlow<String?>("backend-a")
        val pins = DevicePins.bind(InMemorySecureSettingsStore().withDevicePins(), source)
        val missing = ObjectRef("agent", "missing")
        val loaded = ObjectRef("agent", "loaded")
        pins.setPinned(missing, true)
        pins.setPinned(loaded, true)
        pins.setPinned(ObjectRef("conversation", "other"), true)
        val utilities = (1..10).map { "utility-$it" to "Utility $it" }
        var state by mutableStateOf(ShellLibraryRailState(agents = utilities + ("loaded" to "Loaded"), focus = ShellRailFocus()))
        fun bound() = pinnedRailState(state, pins, pins.references.value)

        val entries = collapsedRailEntries(bound().agents, bound().focus)
        assertEquals(listOf("missing", "loaded"), entries.take(2).map { it.agentId })
        assertEquals(DisplayNames.agent(null, "missing"), entries.first().name)
        assertEquals(2 + RecentAgentsPolicy.DEFAULT_MAX_AGENTS, entries.size)
        assertEquals(false, bound().agents.any { it.first == "other" })
        val utilityFacets = utilities.associate { it.first to ObjectFacets(pinned = false, kindTag = "utility") }
        val utilityFocus = bound().focus.copy(objectFacetsByAgentId = bound().focus.objectFacetsByAgentId.orEmpty() + utilityFacets)
        assertEquals(listOf("missing", "loaded"), collapsedRailEntries(bound().agents, utilityFocus).take(2).map { it.agentId })

        setContent {
            MaterialTheme {
                ShellLibraryRail(rememberPinnedRailState(state, pins), ShellAgentRailActions())
            }
        }
        onNodeWithTag(ShellAgentRailTags.orb("pinned-missing")).assertExists()
        onNodeWithTag(ShellAgentRailTags.PIN_DIVIDER).assertExists()

        state = state.copy(agents = state.agents + ("missing" to "Resolved name"))
        waitForIdle()
        assertEquals(listOf("missing", "loaded"), collapsedRailEntries(bound().agents, bound().focus).take(2).map { it.agentId })
        assertEquals("Resolved name", collapsedRailEntries(bound().agents, bound().focus).first().name)
        assertEquals(1, bound().agents.count { it.first == "missing" })

        source.value = "backend-b"
        waitForIdle()
        assertEquals(false, collapsedRailEntries(bound().agents, bound().focus).any { it.pinned })
        onNodeWithTag(ShellAgentRailTags.orb("pinned-missing")).assertDoesNotExist()
        source.value = "backend-a"
        waitForIdle()
        onNodeWithTag(ShellAgentRailTags.orb("pinned-missing")).assertExists()
        pins.setPinned(missing, false)
        waitForIdle()
        onNodeWithTag(ShellAgentRailTags.orb("pinned-missing")).assertDoesNotExist()
    }

    @Test
    fun scopedFacetMapsNeverFallBackToLegacyMembership() {
        val roster = listOf("legacy" to "Legacy", "scoped" to "Scoped")
        val legacyOnly = ShellRailFocus(pinnedAgentIds = setOf("legacy"))
        assertEquals(listOf("legacy"), collapsedRailEntries(roster, legacyOnly).filter { it.pinned }.map { it.agentId })
        val partial = legacyOnly.copy(objectFacetsByAgentId = mapOf("scoped" to ObjectFacets(pinned = true, pinOrder = 0)))
        assertEquals(listOf("scoped"), collapsedRailEntries(roster, partial).filter { it.pinned }.map { it.agentId })
        assertEquals(null, partial.facets("legacy").pinned)
        assertEquals(null, legacyOnly.copy(objectFacetsByAgentId = emptyMap()).facets("legacy").pinned)
    }

    @Test
    fun pinOrderFacetsWinOverRecencyAndSharedNamesAndFollowReorder() {
        val pins = com.letta.mobile.data.objectverbs.binding.DevicePins.bind(
            com.letta.mobile.data.storage.InMemorySecureSettingsStore().withDevicePins(),
            kotlinx.coroutines.flow.MutableStateFlow("backend"),
        )
        val a = com.letta.mobile.data.objectverbs.ObjectRef("agent", "a")
        val b = com.letta.mobile.data.objectverbs.ObjectRef("agent", "b")
        pins.setPinned(b, true)
        pins.setPinned(a, true)
        val roster = listOf("a" to "Scout", "recent" to "Recent", "b" to "Scout")
        fun entries(): List<ShellRailEntry> {
            val facets = listOf(a, b).associate { ref ->
                ref.id to checkNotNull(pins.registry.describe(ref, com.letta.mobile.data.objectverbs.VerbContext("rail"))).facets
            }
            return collapsedRailEntries(roster, ShellRailFocus(selectedAgentId = "a", objectFacetsByAgentId = facets))
        }
        assertEquals(listOf("b", "a", "recent"), entries().map { it.agentId })
        assertEquals(listOf(0, 1), entries().take(2).map { it.pinOrder })
        pins.reorder(listOf(a, b))
        assertEquals(listOf("a", "b", "recent"), entries().map { it.agentId })
        assertEquals(3, entries().map { it.key }.distinct().size)
    }

    @Test
    fun aPersistedPinKeepsItsSlotWhileTenNewerAgentsBecomeActive() {
        val backing = com.letta.mobile.data.storage.InMemorySecureSettingsStore().withDevicePins()
        val source = kotlinx.coroutines.flow.MutableStateFlow<String?>("backend")
        val original = com.letta.mobile.data.objectverbs.binding.DevicePins.bind(backing, source)
        original.setPinned(com.letta.mobile.data.objectverbs.ObjectRef("agent", "anchor"), true)
        val reopened = com.letta.mobile.data.objectverbs.binding.PinBinding(com.letta.mobile.data.pins.DevicePinStore(backing), source)
        val facet = checkNotNull(reopened.registry.describe(
            com.letta.mobile.data.objectverbs.ObjectRef("agent", "anchor"), com.letta.mobile.data.objectverbs.VerbContext("rail"),
        )).facets
        val focus = ShellRailFocus(objectFacetsByAgentId = mapOf("anchor" to facet))
        val anchor = "anchor" to "Anchor"
        assertEquals("anchor", collapsedRailEntries(listOf(anchor), focus).single().agentId)
        for (newer in 1..10) {
            val active = (newer downTo 1).map { "utility-$it" to "Utility $it" } + anchor
            val entries = collapsedRailEntries(active, focus)
            assertEquals("anchor", entries.first().agentId, "pin displaced after $newer newer agents")
            assertEquals(1 + minOf(newer, 8), entries.size)
        }
    }

    @Test
    fun pinnedAgentsLeadAndEachSpaceHoldsTheRest() {
        val groups = agents.map { (id, name) -> AgentRailGroup(name = name, agentIds = listOf(id)) }
        val entries = ShellRailMapping.entries(groups, ShellRailFocus(pinnedAgentIds = setOf("a3")))
        val sections = librarySections(groups, entries, needle = "")
        assertEquals("pinned", sections.first().key)
        assertEquals(listOf("Scout"), sections.first().items.map { it.name })
        assertEquals(false, sections.drop(1).any { section -> section.items.any { it.name == "Scout" } })
        assertEquals(3, sections.drop(1).sumOf { it.items.size })
    }

    @Test
    fun aQueryNarrowsTheLibraryAndNothingMatchingLeavesNoSections() {
        val groups = agents.map { (id, name) -> AgentRailGroup(name = name, agentIds = listOf(id)) }
        val entries = ShellRailMapping.entries(groups, ShellRailFocus())
        assertEquals(listOf("PM - letta-mobile"), librarySections(groups, entries, "pm letta mobile").flatMap { it.items }.map { it.name })
        assertEquals(emptyList(), librarySections(groups, entries, "zzz"))
    }

    @Test
    fun theExpandedRailSearchesAndOpensAnAgent() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ShellLibraryRail(
                    state = ShellLibraryRailState(agents = agents, focus = ShellRailFocus(), expanded = true),
                    actions = ShellAgentRailActions(onAgentSelected = { opened += it }),
                )
            }
        }
        onNodeWithText("Scout").assertExists()
        onNodeWithContentDescription("Search agents").performTextInput("zzz")
        onNodeWithText("No agents match \"zzz\"").assertExists()
        onNodeWithContentDescription("Clear search").performClick()
        onNodeWithText("Meridian").performClick()
        assertEquals(listOf("a4"), opened)
    }

    @Test
    fun collapsedRailIsCappedButExpandedLibraryKeepsAndSearchesTheFullRoster() = runComposeUiTest {
        val roster = (0 until 100).map { index ->
            "agent-$index" to "Agent ${index.toString().padStart(3, '0')}"
        }
        val focus = ShellRailFocus()
        val expectedCollapsed = collapsedRailEntries(roster, focus)
        assertEquals(RecentAgentsPolicy.DEFAULT_MAX_AGENTS, expectedCollapsed.size)
        assertEquals((0..7).map { "Agent ${it.toString().padStart(3, '0')}" }, expectedCollapsed.map { it.name })

        var state by mutableStateOf(ShellLibraryRailState(agents = roster, focus = focus))
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ShellLibraryRail(
                    state = state,
                    actions = ShellAgentRailActions(onAgentSelected = { opened += it }),
                )
            }
        }
        onNodeWithTag(ShellAgentRailTags.orb("Agent 000")).assertExists()
        onNodeWithContentDescription("Search agents").assertDoesNotExist()

        state = state.copy(expanded = true)
        waitForIdle()
        val groups = ShellRailMapping.groups(roster, selectedAgentId = null)
        val entries = ShellRailMapping.entries(groups, state.focus)
        assertEquals(100, librarySections(groups, entries, needle = "").sumOf { it.items.size })
        onNodeWithContentDescription("Search agents").performTextInput("Agent 099")
        onAllNodesWithText("Agent 099")[1].performClick()
        assertEquals(listOf("agent-99"), opened)

        // Collapse after selecting the last roster member. Its panel owns focus; the rail returns
        // to its eight-entry recents cut and does not lose the expanded library's roster.
        state = state.copy(expanded = false, focus = focus.copy(selectedAgentId = "agent-99"))
        waitForIdle()
        assertEquals(100, state.agents.size)
        assertEquals(8, collapsedRailEntries(state.agents, state.focus).size)
        onNodeWithContentDescription("Search agents").assertDoesNotExist()
        onNodeWithTag(ShellAgentRailTags.orb("Agent 000")).assertExists()
    }

    @Test
    fun collapsedOverflowKeepsEveryPinOutsideTheRecentCap() {
        val roster = (0 until 12).map { index ->
            "agent-$index" to "Agent ${index.toString().padStart(2, '0')}"
        }
        val pinned = (0..9).mapTo(mutableSetOf()) { "agent-$it" }
        val entries = collapsedRailEntries(roster, ShellRailFocus(pinnedAgentIds = pinned))

        assertEquals(12, entries.size)
        assertEquals((0..11).map { "Agent ${it.toString().padStart(2, '0')}" }, entries.map { it.name })
        assertEquals(true, entries.take(10).all { it.pinned })
    }

    @Test
    fun pinnedAgentsRemainDistinctAndVisibleWhenFocused() {
        val roster = listOf("a" to "Scout", "b" to "Scout", "c" to "Scout")
        val entries = collapsedRailEntries(roster, ShellRailFocus(selectedAgentId = "a", pinnedAgentIds = setOf("a", "b")))
        assertEquals(listOf("a", "b", "c"), entries.map { it.agentId })
        assertEquals(3, entries.map { it.key }.distinct().size)
    }
}
