package com.letta.mobile.ui.mascot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a mascot stands: the seat it was sent to while that seat exists, else rest. */
class MascotTransportTest {
    private val all = setOf(MascotStage.COMPOSER_COMPANION, MascotStage.AGENT_PANE_HERO, MascotStage.EDIT_AGENT_HERO)

    @Test
    fun `rests at the composer when nothing was requested`() {
        assertEquals(MascotStage.COMPOSER_COMPANION, MascotTransport.activeStage(all, requested = null))
    }

    @Test
    fun `goes where it was sent while that seat exists`() {
        assertEquals(MascotStage.EDIT_AGENT_HERO, MascotTransport.activeStage(all, MascotStage.EDIT_AGENT_HERO))
        assertEquals(MascotStage.AGENT_PANE_HERO, MascotTransport.activeStage(all, MascotStage.AGENT_PANE_HERO))
    }

    @Test
    fun `settles to rest when the requested seat is gone`() {
        val noEditor = all - MascotStage.EDIT_AGENT_HERO
        assertEquals(MascotStage.COMPOSER_COMPANION, MascotTransport.activeStage(noEditor, MascotStage.EDIT_AGENT_HERO))
    }

    @Test
    fun `rest is the lowest seat that exists, not always the composer`() {
        val paneOnly = setOf(MascotStage.AGENT_PANE_HERO)
        assertEquals(MascotStage.AGENT_PANE_HERO, MascotTransport.activeStage(paneOnly, requested = null))
    }

    @Test
    fun `nowhere to stand is null`() {
        assertNull(MascotTransport.activeStage(emptySet(), MascotStage.COMPOSER_COMPANION))
    }

    @Test
    fun `the verb is per agent and rest forgets it`() {
        val transport = MascotTransport()
        transport.transportTo("a", MascotStage.EDIT_AGENT_HERO)
        transport.seats[MascotTransport.SeatKey("a", MascotStage.EDIT_AGENT_HERO)] = seat()
        transport.seats[MascotTransport.SeatKey("a", MascotStage.COMPOSER_COMPANION)] = seat()
        transport.seats[MascotTransport.SeatKey("b", MascotStage.COMPOSER_COMPANION)] = seat()
        assertEquals(MascotStage.EDIT_AGENT_HERO, transport.activeStage("a"))
        assertEquals(MascotStage.COMPOSER_COMPANION, transport.activeStage("b"))
        transport.rest("a")
        assertEquals(MascotStage.COMPOSER_COMPANION, transport.activeStage("a"))
        assertEquals(listOf("a", "b"), transport.agentsSeated().sorted())
    }

    private fun seat() = MascotSeatInfo(androidx.compose.ui.geometry.Rect.Zero, overscale = 1f, identity = null, onClick = null)
}
