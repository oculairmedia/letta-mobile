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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.greeting.GreetingMood
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.mascot.greetingMood
import com.letta.mobile.ui.shell.tokens.ShellDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Home's greeting geometry at every viewport: the line and mark on top, three mascots centred under them, nothing overlapping. */
class HomeGreetingTest {
    private val cast = listOf(HomeAgentRef("a-1", "One"), HomeAgentRef("a-2", "Two"), HomeAgentRef("a-3", "Three"))

    @Test
    fun slotsAreCentredAndNeverOverlapAtEveryViewport() {
        VIEWPORTS.forEach { width ->
            listOf(false, true).forEach { huddled ->
                val slots = greetingSlots(cast.size, width, huddled)
                assertEquals(0f, slots.sumOf { it.value.toDouble() }.toFloat(), 0.01f, "centred at $width")
                slots.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= ShellDimens.homeGreetingMascot, "overlap at $width") }
                val half = ShellDimens.homeGreetingMascot / 2
                assertTrue(slots.first() - half >= -width / 2 && slots.last() + half <= width / 2, "inside the stage at $width")
            }
        }
    }

    @Test
    fun anUnsettledLinkDrawsTheRowIn() {
        val open = greetingSlots(cast.size, 1200.dp, huddled = false)
        val huddled = greetingSlots(cast.size, 1200.dp, huddled = true)
        assertTrue(huddled.last() < open.last())
    }

    @Test
    fun layoutAtEveryViewport() = runComposeUiTest {
        var width by mutableStateOf(VIEWPORTS.first())
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.requiredWidth(width)) { HomeGreeting(cast, HomeGreetingOptions(hour = EVENING), hydrating = false) }
                }
            }
        }
        VIEWPORTS.forEach { viewport ->
            width = viewport
            waitForIdle()
            val line = onNodeWithTag(HomeGreetingTags.LINE).getUnclippedBoundsInRoot()
            val mark = onNodeWithTag(HomeGreetingTags.MARK).getUnclippedBoundsInRoot()
            val stage = onNodeWithTag(HomeGreetingTags.STAGE).getUnclippedBoundsInRoot()
            assertTrue(mark.right <= line.left, "mark before the line at $viewport")
            assertTrue(stage.top >= line.bottom && stage.top >= mark.bottom, "stage under the line at $viewport")
            val mascots = cast.map { onNodeWithTag(HomeGreetingTags.mascot(it.id)).getUnclippedBoundsInRoot() }
            mascots.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left, "mascots overlap at $viewport") }
            assertEquals(viewport.value, (stage.right - stage.left).value, 0.5f, "stage spans the viewport at $viewport")
            mascots.forEach { assertTrue(it.left >= stage.left && it.right <= stage.right, "mascot off the stage at $viewport") }
            val rowCentre = (mascots.first().left + mascots.last().right) / 2
            val stageCentre = (stage.left + stage.right) / 2
            assertTrue(abs((rowCentre - stageCentre).value) <= 1f, "row centred at $viewport")
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
        const val EVENING = 19
    }
}
