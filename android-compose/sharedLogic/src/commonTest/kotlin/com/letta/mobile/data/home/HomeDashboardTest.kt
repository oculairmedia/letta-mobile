package com.letta.mobile.data.home

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeDashboardTest {
    private val conversations = HomePinnedItem.shortcutKey(HomeShortcut.CONVERSATIONS)
    private val tools = HomePinnedItem.shortcutKey(HomeShortcut.TOOLS)

    @Test
    fun pinKeysRoundTripAndKeepTheAndroidFormat() {
        assertEquals("shortcut:CONVERSATIONS", conversations)
        assertEquals("agent:a-1", HomePinnedItem.agentKey("a-1"))
        assertEquals(HomeShortcut.TOOLS, HomePinnedItem.parseShortcutKey(tools))
        assertEquals(null, HomePinnedItem.parseShortcutKey("shortcut:GONE"))
        assertEquals("a-1", HomePinnedItem.parseAgentKey("agent:a-1"))
        assertEquals(null, HomePinnedItem.parseAgentKey(tools))
    }

    @Test
    fun pinsResolveInOrderAndHideWhatTheHostCannotOpen() {
        val items = resolvePinnedItems(
            keys = listOf(tools, "agent:a-1", conversations, tools, "junk"),
            names = PinnedAgentNames(live = mapOf("a-1" to "Scout"), settled = true),
            availableShortcuts = setOf(HomeShortcut.TOOLS),
        )
        assertEquals(
            listOf(HomePinnedItem.Shortcut(HomeShortcut.TOOLS), HomePinnedItem.Agent(HomeAgentRef("a-1", "Scout"))),
            items,
        )
    }

    @Test
    fun anUnknownPinnedAgentShowsItsPersistedNameOnlyWhileAgentsLoad() {
        val keys = listOf("agent:gone")
        val persisted = mapOf("gone" to "Old friend")
        assertEquals(
            listOf(HomePinnedItem.Agent(HomeAgentRef("gone", "Old friend"))),
            resolvePinnedItems(keys, PinnedAgentNames(emptyMap(), settled = false, persisted = persisted)),
        )
        assertTrue(resolvePinnedItems(keys, PinnedAgentNames(emptyMap(), settled = true, persisted = persisted)).isEmpty())
    }

    @Test
    fun usageSumsTokensAndRanksModels() {
        val summary = HomeUsageCalculator.calculate(
            listOf(
                Step(id = "s1", model = "gpt", totalTokens = 300),
                Step(id = "s2", model = "claude", promptTokens = 600, completionTokens = 100),
                Step(id = "s3", model = "", totalTokens = 100),
                Step(id = "s4", model = "gpt", totalTokens = 0),
            ),
            windowHours = 10,
        )
        assertEquals(1_100, summary.totalTokens)
        assertEquals(110, summary.averageTokensPerHour)
        assertEquals(3, summary.sampledSteps)
        assertEquals(listOf("claude", "gpt", "Unknown model"), summary.modelUsage.map { it.model })
        assertEquals(64, summary.modelUsage.first().sharePercent)
    }

    @Test
    fun noUsageIsAnEmptySummary() {
        val summary = HomeUsageCalculator.calculate(emptyList())
        assertEquals(0, summary.totalTokens)
        assertTrue(summary.modelUsage.isEmpty())
    }

    @Test
    fun catalogSearchMatchesNamesDescriptionsAndBlockValues() {
        val catalog = HomeSearchCatalog(
            agents = listOf(Agent(id = AgentId("a-1"), name = "Planner"), Agent(id = AgentId("a-2"), name = "Ops")),
            tools = listOf(Tool(id = ToolId("t-1"), name = "web_search", description = "Plan trips")),
            blocks = listOf(
                Block(id = BlockId("b-1"), label = "notes", value = "the PLAN"),
                Block(id = BlockId("b-2"), label = "other", value = "nothing"),
            ),
        )
        val result = searchHomeCatalog(catalog, "plan")
        assertEquals(listOf("Planner"), result.agents.map { it.name })
        assertEquals(listOf("web_search"), result.tools.map { it.name })
        assertEquals(listOf("b-1"), result.blocks.map { it.id.value })
        assertTrue(result.isActive)
        assertFalse(searchHomeCatalog(catalog, "  ").isActive)
        assertTrue(searchHomeCatalog(catalog, "zzz").isEmpty)
    }

    @Test
    fun statTilesDashWhileLoadingAndDropFiguresTheBackendLacks() {
        val fleet = FleetOverview(summary = FleetSummary(totalAgents = 2, totalConversations = 9, activeToday = 1, agentsActiveByDay = listOf(0, 1)))
        val loading = HomePageReducer.withFleet(HomePageState(), fleet).statTiles()
        assertEquals(
            listOf("Agents", "Conversations", "Active today", "Running now", "Tools", "Blocks", "Tokens · 24h"),
            loading.map { it.label },
        )
        assertEquals("—", loading.last().value)
        assertEquals(listOf(0, 1), loading[2].series)

        val usage = HomeUsageSummary(totalTokens = 4_800, averageTokensPerHour = 200, sampledSteps = 3, modelUsage = listOf(ModelTokenUsage("openai/gpt-5", 4_000, 83)))
        val settled = HomePageState(fleet = fleet, stats = HomeStats(loading = false, toolCount = 1_204, usage = usage)).statTiles()
        assertEquals(listOf("Agents", "Conversations", "Active today", "Running now", "Tools", "Tokens · 24h"), settled.map { it.label })
        assertEquals("1,204", settled[4].value)
        assertEquals("4,800", settled[5].value)
        assertEquals("~200/h · gpt-5 83%", settled[5].caption)
    }

    @Test
    fun numbersAreGroupedByThousands() {
        assertEquals("0", formatGroupedNumber(0))
        assertEquals("999", formatGroupedNumber(999))
        assertEquals("1,000", formatGroupedNumber(1_000))
        assertEquals("12,345,678", formatGroupedNumber(12_345_678))
        assertEquals("-4,200", formatGroupedNumber(-4_200))
    }

    @Test
    fun theSettingsPinStoreStartsFromDefaultsAndPersistsChanges() {
        val store = MapSettingsStore()
        val pins = SettingsStoreHomePinStore(store, defaults = listOf(conversations))
        assertEquals(listOf(conversations), pins.pinnedKeys.value)

        pins.setPinned(tools, pinned = true)
        pins.setPinned(tools, pinned = true)
        assertEquals(listOf(conversations, tools), pins.pinnedKeys.value)
        pins.setOrder(listOf(tools, conversations))
        assertEquals(listOf(tools, conversations), SettingsStoreHomePinStore(store, emptyList()).pinnedKeys.value)

        pins.setPinned(tools, pinned = false)
        pins.setPinned(conversations, pinned = false)
        assertEquals(emptyList(), SettingsStoreHomePinStore(store, listOf(conversations)).pinnedKeys.value, "an emptied grid stays empty")
    }

    private class MapSettingsStore : SecureSettingsStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun clear() {
            values.clear()
        }
    }
}
