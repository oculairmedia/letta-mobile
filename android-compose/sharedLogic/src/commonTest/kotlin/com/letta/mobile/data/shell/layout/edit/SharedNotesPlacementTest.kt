package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The person's shared-notes toggle is an ordinary checked layout edit beside the page. */
class SharedNotesPlacementTest {
    private val page = ModuleSlot(ShellViews.PAGE)
    private val sidePane = ModuleSlot(ShellViews.SIDE_PANE)

    private fun applied(config: ShellLayoutConfig) = assertIs<ShellLayoutEditResult.Applied>(SharedNotesPlacement.toggled(config)).after

    @Test
    fun showingPutsTheViewFirstInTheSideLaneAndKeepsThePage() {
        val shown = applied(ShellLayoutConfig.Default)

        assertEquals(listOf(SharedNotesPlacement.SLOT, sidePane), shown.sideLane())
        assertEquals(listOf(page), shown.mainLane())
        assertTrue(SharedNotesPlacement.shown(shown))
        assertTrue(SharedNotesPlacement.inSideLane(shown))
    }

    @Test
    fun hidingTakesItOutOfWhicheverLaneHoldsIt() {
        val inMain = ShellLayoutConfig.Default.copy(
            lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.MAIN to listOf(page, SharedNotesPlacement.SLOT)),
        )
        assertTrue(SharedNotesPlacement.shown(inMain))
        assertFalse(SharedNotesPlacement.inSideLane(inMain))

        val hidden = applied(inMain)
        assertEquals(listOf(page), hidden.mainLane())
        assertFalse(SharedNotesPlacement.shown(hidden))
        assertFalse(SharedNotesPlacement.shown(applied(applied(ShellLayoutConfig.Default))))
    }

    @Test
    fun aFullSideLaneRefusesRatherThanDroppingAModule() {
        val full = ShellLayoutConfig.Default.copy(
            lanes = ShellLayoutConfig.Default.lanes + (
                ShellLanes.SIDE to listOf(sidePane, ModuleSlot(ShellViews.TASKS), ModuleSlot(ShellViews.CANVAS, "board"))
            ),
        )
        assertIs<ShellLayoutEditResult.Rejected>(SharedNotesPlacement.toggled(full))
    }

    @Test
    fun anAgentMayPlaceTheViewInTheMainOrSideLaneWithoutAnObject() {
        val main = ShellLayoutCatalog.lane(ShellLanes.MAIN)!!.view(ShellViews.DOCUMENT)
        val side = ShellLayoutCatalog.lane(ShellLanes.SIDE)!!.view(ShellViews.DOCUMENT)
        assertFalse(main!!.needsObject)
        assertEquals(main, side)
        val withObject = ShellLayoutOp.Add(ShellLanes.SIDE, ModuleSlot(ShellViews.DOCUMENT, "note"))
        val refused = assertIs<ShellLayoutEditResult.Rejected>(ShellLayoutEdits.apply(ShellLayoutConfig.Default, listOf(withObject)))
        assertEquals(ShellLayoutEditRule.OBJECT_NOT_ALLOWED, refused.errors.single().rule)
    }
}
