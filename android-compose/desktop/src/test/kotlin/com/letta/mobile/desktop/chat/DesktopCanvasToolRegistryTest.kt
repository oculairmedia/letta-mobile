package com.letta.mobile.desktop.chat

import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.CanvasToolContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One owner for canvas.* on a runtime: the Iroh host there, the desktop against a direct App Server (letta-mobile-aknkw.4). */
class DesktopCanvasToolRegistryTest {
    @Test
    fun onIrohTheDesktopOffersNoCanvasToolsOfItsOwn() {
        assertTrue(desktopCanvasToolRegistry(isIroh = true, canvasSessions = CanvasSessionRegistry()).listAdvertisedTools().isEmpty())
    }

    @Test
    fun againstADirectAppServerItOffersAllSix() {
        val names = desktopCanvasToolRegistry(isIroh = false, canvasSessions = CanvasSessionRegistry()).listAdvertisedTools().map { it.name }
        assertEquals(CanvasToolContract.all.map { it.name }.toSet(), names.toSet())
    }
}
