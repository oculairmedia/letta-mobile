@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * letta-mobile-bglj6.1.12: a seated mascot rests. The shared chat keeps the agent's mascot seated
 * at the composer for the life of the page; while it moved on every frame regardless, an idle
 * phone chat redrew the whole window every vsync (Rive TextureView). These pin the rule: the scene
 * advances while there is something to show and for [MASCOT_REST_DELAY] after, then holds still
 * and asks for no frames.
 */
class MascotRestUiTest {
    private val agent = "agent-1"

    /** Records what [MascotHost.Surface] was last asked for. */
    private class RecordingMascotHost : MascotHost {
        var lastPlaying: Boolean? = null

        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry =
            object : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}) {
                override suspend fun load() = Unit
                override fun writeIdentity(identity: MascotIdentity) = Unit
                override fun dispose() = Unit
            }

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            lastPlaying = playing
            Box(modifier)
        }
    }

    private fun ComposeUiTest.showSeat(shell: FakeMascotShell) {
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                // The layer is not mounted, so the seat draws the live mascot in place.
                MascotSeat(agent, MascotStage.COMPOSER_COMPANION, 40.dp, empty = {})
            }
        }
        mainClock.advanceTimeByFrame()
    }

    @Test
    fun idleSeatedMascotStopsAskingForFramesAfterTheRestDelay() = runComposeUiTest {
        val host = RecordingMascotHost()
        val shell = FakeMascotShell(agent, layerMounted = false, host = host)
        showSeat(shell)
        assertEquals(true, host.lastPlaying, "a mascot that just appeared plays its first beat")

        mainClock.advanceTimeBy(MASCOT_REST_DELAY.inWholeMilliseconds + REST_MARGIN_MILLIS)
        assertEquals(false, host.lastPlaying, "an idle mascot with no pointer holds its pose")
        mainClock.advanceTimeBy(MASCOT_REST_DELAY.inWholeMilliseconds * 3)
        assertEquals(false, host.lastPlaying, "and keeps holding it")
    }

    @Test
    fun workingMascotKeepsMovingAndRestsAfterTheRunEnds() = runComposeUiTest {
        val host = RecordingMascotHost()
        val shell = FakeMascotShell(agent, layerMounted = false, host = host)
        shell.registry.setPresence(agent, AgentPresence(activity = AgentActivityKind.entries.first { it != AgentActivityKind.IDLE }))
        showSeat(shell)

        mainClock.advanceTimeBy(MASCOT_REST_DELAY.inWholeMilliseconds * 3)
        assertEquals(true, host.lastPlaying, "a working agent's mascot keeps moving")

        shell.registry.clearPresence(agent)
        mainClock.advanceTimeBy(MASCOT_REST_DELAY.inWholeMilliseconds - REST_MARGIN_MILLIS)
        assertEquals(true, host.lastPlaying, "the last beat (the success bloom) plays out after the run")
        mainClock.advanceTimeBy(REST_MARGIN_MILLIS * 2)
        assertEquals(false, host.lastPlaying, "then the mascot rests")
    }

    @Test
    fun pointerInTheWindowKeepsTheEyesFollowing() = runComposeUiTest {
        val host = RecordingMascotHost()
        val shell = FakeMascotShell(agent, layerMounted = false, host = host)
        shell.registry.cursor.value = Offset(10f, 10f)
        showSeat(shell)

        mainClock.advanceTimeBy(MASCOT_REST_DELAY.inWholeMilliseconds * 3)
        assertEquals(true, host.lastPlaying, "with a pointer to follow the mascot stays live")
        shell.registry.cursor.value = null
        mainClock.advanceTimeBy(MASCOT_REST_DELAY.inWholeMilliseconds + REST_MARGIN_MILLIS)
        assertFalse(host.lastPlaying == true, "the pointer left: the mascot rests")
    }

    private companion object {
        const val REST_MARGIN_MILLIS = 200L
    }
}
