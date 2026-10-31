@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetOverview
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.FleetSummary
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageReducer
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.home.HomeStats
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Home with a realistic fleet, rendered to build/home-render/ for review (letta-mobile-ob7z8),
 * plus the Today / Yesterday / Previous 7 days / Earlier bucketing of the recents list.
 */
class HomePageRenderTest {
    private val now = Clock.System.now()

    @Test
    fun recentsBucketByLocalDayKeepingOrder() {
        val noon = Instant.parse("2026-10-30T12:00:00Z")
        val rows = listOf(
            conversation("c-0", null),
            conversation("c-1", noon - 2.hours),
            conversation("c-2", noon - 1.days),
            conversation("c-3", noon - 3.days),
            conversation("c-4", noon - 30.days),
            conversation("c-5", noon - 3.hours),
        )
        val groups = recentDayGroups(rows, noon, TimeZone.UTC)
        assertEquals(RecentDay.entries, groups.map { it.day })
        assertEquals(listOf("c-0", "c-1", "c-5"), groups.first().conversations.map { it.conversationId }, "undated rows count as today")
        assertEquals(emptyList(), recentDayGroups(emptyList(), noon, TimeZone.UTC))
    }

    @Test
    fun wideHomeRendersRecentsAboveTheCounters() = render("home-wide.png", WIDE_PX, HEIGHT_PX)

    @Test
    fun compactHomeRendersRecentsAboveTheCounters() = render("home-compact.png", COMPACT_PX, HEIGHT_PX)

    private fun render(name: String, width: Int, height: Int) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                ProvideLettaHost(LettaHost.Pointer) {
                    Surface(Modifier.fillMaxSize()) {
                        HomePage(state = state(), callbacks = HomePageCallbacks(NoActions, navigation))
                    }
                }
            }
        }
        waitForIdle()
        val recents = onNodeWithTag(HomePageTags.RECENTS).getUnclippedBoundsInRoot().top
        val stats = onNodeWithTag(HomePageTags.STATS_STRIP).getUnclippedBoundsInRoot().top
        assertTrue(recents < stats, "recents ($recents) sit above the stats strip ($stats)")
        val file = File("build/home-render").apply { mkdirs() }.resolve(name)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        println("home snapshot: ${file.absolutePath}")
    }

    private fun state(): HomePageState {
        val agents = listOf("Scout", "Planner", "Archivist", "Courier", "Sage").mapIndexed { index, name ->
            FleetAgentStat(
                agentId = "a-$index",
                name = name,
                model = "openai/gpt-5",
                conversationCount = 10 - index,
                lastActivity = now,
                running = index == 1,
                activityByDay = List(14) { it % 3 },
            )
        }
        val ages = listOf(4.minutes, 50.minutes, 3.hours, 26.hours, 30.hours, 3.days, 5.days, 20.days)
        val titles = listOf(
            "Trip planning", "Quarterly review notes", "Fix the flaky login test", "Groceries",
            "Reading list", "Garden layout", "Tax documents", "Birthday ideas",
        )
        val recent = titles.mapIndexed { index, title ->
            val at = now - ages[index]
            val agent = agents[index % agents.size]
            FleetRecentConversation("c-$index", agent.agentId, agent.name, title, "Last message preview for $title", at, at.toString())
        }
        val summary = FleetSummary(totalAgents = 5, totalConversations = 42, activeToday = 2, runningNow = 1)
        val fleet = FleetOverview(summary = summary, agents = agents, recent = recent)
        val withFleet = HomePageReducer.withFleet(HomePageState(), fleet)
        val withPins = HomePageReducer.withPins(withFleet, listOf("shortcut:SCHEDULES", "agent:a-2"))
        return HomePageReducer.withStats(withPins, HomeStats(loading = false, toolCount = 14, blockCount = 1441))
    }

    private fun conversation(id: String, at: Instant?) =
        FleetRecentConversation(id, "a-1", "Scout", id, "", at, at?.toString() ?: "Queued")

    private val navigation = HomePageNavigation(onSubmitPrompt = {}, onOpenConversation = {}, onOpenAgent = {}, onOpenShortcut = {})

    private object NoActions : HomePageActions {
        override fun loadIfStale() = Unit
        override fun refresh() = Unit
        override fun updateSearchQuery(query: String) = Unit
        override fun clearSearch() = Unit
        override fun selectSort(key: FleetSortKey) = Unit
        override fun reorderPins(keys: List<String>) = Unit
        override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit
        override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
    }

    private companion object {
        const val WIDE_PX = 1200
        const val COMPACT_PX = 420
        const val HEIGHT_PX = 1400
    }
}
