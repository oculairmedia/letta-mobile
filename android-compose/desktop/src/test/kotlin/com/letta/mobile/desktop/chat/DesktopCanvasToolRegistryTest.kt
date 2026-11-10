package com.letta.mobile.desktop.chat

import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.shell.layout.edit.ShellLayoutTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One owner for canvas_* on a runtime: the Iroh host there, the desktop against a direct App Server (letta-mobile-aknkw.4). */
class DesktopCanvasToolRegistryTest {
    /** Every tool the desktop itself answers against a direct App Server: canvas_* and the shell layout tool. */
    private val ownTools = CanvasToolContract.all.map { it.name }.toSet() + ShellLayoutTool.NAME

    @Test
    fun onIrohTheDesktopOffersNoCanvasToolsOfItsOwn() {
        assertTrue(desktopCanvasToolRegistry(isIroh = true, canvasSessions = CanvasSessionRegistry()).listAdvertisedTools().isEmpty())
    }

    @Test
    fun againstADirectAppServerItOffersEveryCanvasTool() {
        val names = desktopCanvasToolRegistry(isIroh = false, canvasSessions = CanvasSessionRegistry()).listAdvertisedTools().map { it.name }
        assertEquals(ownTools, names.toSet())
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
        assertEquals(ownTools, offered("cli").toSet())
        assertEquals(ownTools, offered("native").toSet())
        assertEquals(ownTools, offered("bogus").toSet(), "an invalid value stays native")
    }
}
