@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.greeting.GreetingMood
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.mascot.greetingMood
import com.letta.mobile.ui.shell.tokens.ShellDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home's greeting at every viewport: two lines over a tight, organic lineup. Bodies sit within a
 * step of each other, never overlap past a set share, never leave the stage, and stand the same way
 * every time for the same agents.
 */
class HomeGreetingTest {
    private val pool = (1..GREETING_POOL).map { HomeAgentRef("agent-$it", "Agent $it") }
    private val members = pool.map { LineupMember(it.id) }

    private fun body(spot: LineupSpot): Pair<Dp, Dp> {
        val width = spot.size * ShellDimens.homeLineupBodyShare
        val left = spot.x + (spot.size - width) / 2
        return left to left + width
    }

    @Test
    fun theLineupIsTightAtEveryViewport() {
        VIEWPORTS.forEach { width ->
            listOf(false, true).forEach { huddled ->
                val spots = greetingLineup(members, width, huddled)
                spots.map(::body).zipWithNext().forEach { (a, b) ->
                    val gap = b.first - a.second
                    assertTrue(gap <= ShellDimens.homeLineupNudge, "gap $gap at $width")
                    val smaller = min(a.second - a.first, b.second - b.first)
                    assertTrue(-gap <= smaller * MAX_OVERLAP_SHARE, "overlap ${-gap} at $width")
                }
                spots.map(::body).forEach { (left, right) -> assertTrue(left >= 0.dp && right <= width, "clipped at $width") }
            }
        }
    }

    @Test
    fun aWiderStageFitsMoreOfTheCrew() {
        val counts = VIEWPORTS.map { greetingLineup(members, it).size }
        assertTrue(counts.first() in PHONE_CAST, "phone cast ${counts.first()}")
        assertEquals(counts.sorted(), counts)
        assertTrue(counts.last() > counts.first())
    }

    @Test
    fun theCrewStandsTheSameWayEveryVisitAndVaries() {
        val once = greetingLineup(members, VIEWPORTS.last())
        assertEquals(once, greetingLineup(members, VIEWPORTS.last()))
        assertTrue(once.map { it.size }.distinct().size > 1, "sizes vary")
        assertTrue(once.map { it.lift }.distinct().size > 1, "heights vary")
        assertTrue(once.map { it.tilt }.distinct().size > 1, "tilts vary")
        once.forEach { assertTrue(it.lift in -ShellDimens.homeLineupLift..ShellDimens.homeLineupLift) }
    }

    @Test
    fun aBusyAgentStandsBiggerLowerAndInFront() {
        members.forEach { member ->
            val idle = greetingLineup(listOf(member), VIEWPORTS.last()).single()
            val busy = greetingLineup(listOf(member.copy(busy = true)), VIEWPORTS.last()).single()
            assertTrue(busy.size >= idle.size && busy.lift >= idle.lift && busy.front && !idle.front)
        }
    }

    @Test
    fun layoutAtEveryViewport() = runComposeUiTest {
        var width by mutableStateOf(VIEWPORTS.first())
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.requiredWidth(width)) {
                        HomeGreeting(GreetingCast(pool), HomeGreetingOptions(hour = EVENING), hydrating = false)
                    }
                }
            }
        }
        VIEWPORTS.forEach { viewport ->
            width = viewport
            waitForIdle()
            val line = onNodeWithTag(HomeGreetingTags.LINE).getUnclippedBoundsInRoot()
            val status = onNodeWithTag(HomeGreetingTags.STATUS).getUnclippedBoundsInRoot()
            val stage = onNodeWithTag(HomeGreetingTags.STAGE).getUnclippedBoundsInRoot()
            assertTrue(status.top >= line.bottom && stage.top >= status.bottom, "two lines over the stage at $viewport")
            assertEquals(GreetingStageHeight.value, (stage.bottom - stage.top).value, 0.5f, "stage height at $viewport")
            // The placeholder mark is not drawn: it would read as broken.
            assertEquals(0, onAllNodesWithTag(HomeGreetingTags.MARK).fetchSemanticsNodes().size)
            val expected = greetingLineup(members, viewport).size
            val tiles = pool.take(expected).map { onNodeWithTag(HomeGreetingTags.mascot(AgentId(it.id))).getUnclippedBoundsInRoot() }
            tiles.forEach { tile ->
                val inset = (tile.right - tile.left) * (1f - ShellDimens.homeLineupBodyShare) / 2
                val inside = tile.left + inset >= stage.left - TOLERANCE && tile.right - inset <= stage.right + TOLERANCE
                assertTrue(inside, "clipped at $viewport")
            }
            // Whoever does not fit is not drawn at all, rather than squeezed or cut off.
            if (expected < pool.size) {
                assertEquals(0, onAllNodesWithTag(HomeGreetingTags.mascot(AgentId(pool[expected].id))).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun presenceFoldsIntoAMood() {
        assertEquals(GreetingMood.IDLE, null.greetingMood())
        assertEquals(GreetingMood.ERROR, AgentPresence(activity = AgentActivityKind.WORKING, error = true).greetingMood())
        assertEquals(GreetingMood.WAITING, AgentPresence(awaitingApproval = true).greetingMood())
        assertEquals(GreetingMood.THINKING, AgentPresence(activity = AgentActivityKind.THINKING).greetingMood())
        assertEquals(GreetingMood.WORKING, AgentPresence(activity = AgentActivityKind.SPEAKING).greetingMood())
        assertEquals(GreetingMood.ASLEEP, AgentPresence(dreaming = true).greetingMood())
    }

    private companion object {
        val VIEWPORTS: List<Dp> = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        const val GREETING_POOL = 12
        const val EVENING = 19
        const val MAX_OVERLAP_SHARE = 0.15f
        val PHONE_CAST = 4..7
        /** A tilted tile's bounds grow by about this much; the body inside it does not. */
        val TOLERANCE = 2.dp
    }
}
