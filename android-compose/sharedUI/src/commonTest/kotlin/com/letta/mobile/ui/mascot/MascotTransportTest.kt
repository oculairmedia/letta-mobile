package com.letta.mobile.ui.mascot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a mascot stands: the seat it was sent to while that seat exists, else rest. */
class MascotTransportTest {
    private val all = setOf(MascotStage.WELCOME_HERO, MascotStage.COMPOSER_COMPANION, MascotStage.AGENT_PANE_HERO)

    @Test
    fun `rests at the lowest seat when nothing was requested`() {
        assertEquals(MascotStage.WELCOME_HERO, MascotTransport.activeStage(all, requested = null))
        assertEquals(MascotStage.COMPOSER_COMPANION, MascotTransport.activeStage(all - MascotStage.WELCOME_HERO, requested = null))
    }

    @Test
    fun `goes where it was sent while that seat exists`() {
        assertEquals(MascotStage.AGENT_PANE_HERO, MascotTransport.activeStage(all, MascotStage.AGENT_PANE_HERO))
        assertEquals(MascotStage.COMPOSER_COMPANION, MascotTransport.activeStage(all, MascotStage.COMPOSER_COMPANION))
    }

    @Test
    fun `settles to rest when the requested seat is gone`() {
        val noPane = all - MascotStage.AGENT_PANE_HERO - MascotStage.WELCOME_HERO
        assertEquals(MascotStage.COMPOSER_COMPANION, MascotTransport.activeStage(noPane, MascotStage.AGENT_PANE_HERO))
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
        transport.transportTo("a", MascotStage.AGENT_PANE_HERO)
        transport.seats[MascotTransport.SeatKey("a", MascotStage.AGENT_PANE_HERO)] = seat()
        transport.seats[MascotTransport.SeatKey("a", MascotStage.COMPOSER_COMPANION)] = seat()
        transport.seats[MascotTransport.SeatKey("b", MascotStage.COMPOSER_COMPANION)] = seat()
        assertEquals(MascotStage.AGENT_PANE_HERO, transport.activeStage("a"))
        assertEquals(MascotStage.COMPOSER_COMPANION, transport.activeStage("b"))
        transport.rest("a")
        assertEquals(MascotStage.COMPOSER_COMPANION, transport.activeStage("a"))
        assertEquals(listOf("a", "b"), transport.agentsSeated().sorted())
    }

    private fun seat() = MascotSeatInfo(androidx.compose.ui.geometry.Rect.Zero, overscale = 1f, identity = null, handlers = SeatHandlers())
}
