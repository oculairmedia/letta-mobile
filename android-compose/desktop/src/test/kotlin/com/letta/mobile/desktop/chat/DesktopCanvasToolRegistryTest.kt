package com.letta.mobile.desktop.chat

import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.CanvasToolContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One owner for canvas_* on a runtime: the Iroh host there, the desktop against a direct App Server (letta-mobile-aknkw.4). */
class DesktopCanvasToolRegistryTest {
    @Test
    fun onIrohTheDesktopOffersNoCanvasToolsOfItsOwn() {
        assertTrue(desktopCanvasToolRegistry(isIroh = true, canvasSessions = CanvasSessionRegistry()).listAdvertisedTools().isEmpty())
    }

    @Test
    fun againstADirectAppServerItOffersEveryCanvasTool() {
        val names = desktopCanvasToolRegistry(isIroh = false, canvasSessions = CanvasSessionRegistry()).listAdvertisedTools().map { it.name }
        assertEquals(CanvasToolContract.all.map { it.name }.toSet(), names.toSet())
        // letta-mobile-bglj6.12: canvas_compose and its guide among them.
        assertTrue(CanvasToolContract.COMPOSE in names && CanvasToolContract.COMPOSE_GUIDE in names, "$names")
    }

    /** letta-mobile-jna0o.9: meta offers the one meridian tool; cli has no front door here, so it stays native. */
    @Test
    fun theAgentToolsModeDecidesWhatADirectAppServerIsOffered() {
        fun offered(mode: String) = desktopCanvasToolRegistry(
            isIroh = false,
            canvasSessions = CanvasSessionRegistry(),
            agentToolsModes = desktopAgentToolsModePolicy { property, _ -> mode.takeIf { property == "letta.agentToolsMode" } },
        ).offeredTools().map { it.name }

        assertEquals(listOf("meridian"), offered("meta"))
        assertEquals(CanvasToolContract.all.map { it.name }.toSet(), offered("cli").toSet())
        assertEquals(CanvasToolContract.all.map { it.name }.toSet(), offered("native").toSet())
        assertEquals(CanvasToolContract.all.map { it.name }.toSet(), offered("bogus").toSet(), "an invalid value stays native")
    }
}
