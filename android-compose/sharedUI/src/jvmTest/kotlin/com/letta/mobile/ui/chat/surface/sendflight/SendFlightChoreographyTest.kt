@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.sendflight

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-cc25e: the send flight's choreography on a controlled clock. A stand-in field
 * at the bottom and a stand-in prompt row at the top use the real source/target bindings,
 * layer and actions wrapper, so the frames asserted here are the frames the page draws.
 */
class SendFlightChoreographyTest {
    private class Harness {
        var showRow by mutableStateOf(false)
        var rowText by mutableStateOf(SENT)
        lateinit var state: SendFlightState
        lateinit var actions: ChatActions
        val recorded = RecordingChatActions()
    }

    private fun ComposeUiTest.mount(harness: Harness, reducedMotion: Boolean = false) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                MaterialTheme {
                    val state = rememberSendFlightState().also { harness.state = it }
                    SendFlightLayer(state, Modifier.fillMaxSize()) {
                        harness.actions = rememberSendFlightActions(harness.recorded, SENT)
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (harness.showRow) {
                                    Box(
                                        Modifier.align(Alignment.TopStart)
                                            .fillMaxWidth()
                                            .then(rememberSendFlightTarget(ROW, harness.rowText))
                                            .height(ROW_HEIGHT.dp)
                                            .testTag(ROW),
                                    )
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(FIELD_HEIGHT.dp).then(rememberSendFlightSource()).testTag(FIELD))
                        }
                    }
                }
            }
        }
        frames(2)
    }

    private fun ComposeUiTest.frames(count: Int) = repeat(count) { mainClock.advanceTimeByFrame() }

    private fun ComposeUiTest.bounds(tag: String): Rect =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().single().boundsInRoot

    private fun ComposeUiTest.ghostCount(): Int =
        onAllNodes(hasTestTag(SendFlightTestTags.GHOST)).fetchSemanticsNodes().size

    /** The owner adds the prompt row in the same turn as the send, as a real owner does. */
    private fun ComposeUiTest.send(harness: Harness, addRow: Boolean = true) {
        runOnUiThread {
            harness.actions.send()
            if (addRow) harness.showRow = true
        }
    }

    @Test
    fun ghostTakesOffFromTheFieldTravelsAndHandsOffToTheRow() = runComposeUiTest {
        val harness = Harness()
        mount(harness)
        val field = bounds(FIELD)

        send(harness)
        // The send is never held for the animation.
        assertEquals(1, harness.recorded.count("send"))
        frames(1)
        assertEquals(1, ghostCount(), "the ghost appears at send")
        assertClose(field.top, bounds(SendFlightTestTags.GHOST).top, "the ghost takes off from the field")

        mainClock.advanceTimeBy(ChatMotionTokens.SendFlight.FLIGHT_MILLIS / 2L)
        val flight = assertNotNull(harness.state.flight)
        assertEquals(SendFlightPhase.Flying, flight.phase)
        val midway = bounds(SendFlightTestTags.GHOST).top
        val row = bounds(ROW)
        assertTrue(midway < field.top && midway > row.top, "mid-flight the ghost is between field and row ($midway)")
        assertEquals(0f, flight.rowAlpha, "the real row stays hidden while the ghost stands in for it")

        mainClock.advanceTimeBy(ChatMotionTokens.SendFlight.FLIGHT_MILLIS / 2L + FRAME_SLACK)
        assertEquals(SendFlightPhase.HandingOff, flight.phase)
        assertClose(row.top, bounds(SendFlightTestTags.GHOST).top, "the ghost lands on the row")
        assertEquals(1f, flight.insert, "the row's slot is fully open by landing")

        mainClock.advanceTimeBy(ChatMotionTokens.SendFlight.HANDOFF_MILLIS + FRAME_SLACK)
        assertNull(harness.state.flight, "the flight ends after the hand-off")
        assertEquals(0, ghostCount(), "the ghost is gone")
        assertEquals(1f, harness.state.rowAlpha(SendFlightRowKey()), "rows outside a flight are fully shown")
        assertClose(ROW_HEIGHT.toFloat() * density.density, bounds(ROW).height, "the row has its full height")
    }

    @Test
    fun reducedMotionSendsWithoutAFlight() = runComposeUiTest {
        val harness = Harness()
        mount(harness, reducedMotion = true)

        send(harness)
        frames(2)
        assertEquals(1, harness.recorded.count("send"))
        assertNull(harness.state.flight)
        assertEquals(0, ghostCount())
    }

    @Test
    fun ghostFadesWhereItStandsWhenNoRowArrives() = runComposeUiTest {
        val harness = Harness()
        mount(harness)
        val field = bounds(FIELD)

        send(harness, addRow = false)
        frames(1)
        mainClock.advanceTimeBy(ChatMotionTokens.SendFlight.TARGET_WAIT_MILLIS - TIMEOUT_MARGIN)
        assertEquals(SendFlightPhase.Awaiting, harness.state.flight?.phase)
        assertClose(field.top, bounds(SendFlightTestTags.GHOST).top, "the ghost holds at the field while it waits")

        mainClock.advanceTimeBy(TIMEOUT_MARGIN + FRAME_SLACK)
        assertEquals(SendFlightPhase.Abandoned, harness.state.flight?.phase)

        mainClock.advanceTimeBy(ChatMotionTokens.SendFlight.ABANDON_FADE_MILLIS + FRAME_SLACK)
        assertNull(harness.state.flight)
        assertEquals(0, ghostCount())
    }

    @Test
    fun aRowAlreadyOnScreenNeverClaimsTheFlight() = runComposeUiTest {
        val harness = Harness()
        harness.showRow = true
        mount(harness)

        send(harness)
        frames(2)
        val flight = assertNotNull(harness.state.flight)
        assertNull(flight.target, "the older row with the same text is not the new prompt")
        assertEquals(SendFlightPhase.Awaiting, flight.phase)
    }

    @Test
    fun aNewRowWithOtherTextNeverClaimsTheFlight() = runComposeUiTest {
        val harness = Harness()
        harness.rowText = "something else"
        mount(harness)

        send(harness)
        frames(2)
        assertNull(assertNotNull(harness.state.flight).target)
    }

    private fun assertClose(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) <= PIXEL_TOLERANCE, "$message: expected $expected, was $actual")

    private companion object {
        const val SENT = "Plan a taco night for six"
        const val ROW = "test-row"
        const val FIELD = "test-field"
        const val ROW_HEIGHT = 60
        const val FIELD_HEIGHT = 48
        const val FRAME_SLACK = 64L
        const val TIMEOUT_MARGIN = 100L
        const val PIXEL_TOLERANCE = 1.5f
    }
}
