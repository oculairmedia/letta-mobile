package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The layout edit rules: what each lane may hold, the page, Settings and the rail's Home kept, no
 * duplicates, the lane caps, and all-or-nothing application.
 */
class ShellLayoutEditsTest {
    private val default = ShellLayoutConfig.Default
    private val page = ModuleSlot(ShellViews.PAGE)
    private val sidePane = ModuleSlot(ShellViews.SIDE_PANE)
    private fun canvas(id: String) = ModuleSlot(ShellViews.CANVAS, id)
    private val notes = canvas("canvas-notes")

    private fun applied(config: ShellLayoutConfig, vararg ops: ShellLayoutOp): ShellLayoutConfig =
        assertIs<ShellLayoutEditResult.Applied>(ShellLayoutEdits.apply(config, ops.toList())).after

    private fun rejected(config: ShellLayoutConfig, vararg ops: ShellLayoutOp): List<ShellLayoutEditError> =
        assertIs<ShellLayoutEditResult.Rejected>(ShellLayoutEdits.apply(config, ops.toList())).errors

    private fun rules(errors: List<ShellLayoutEditError>) = errors.map { it.opIndex to it.rule }

    @Test
    fun setLaneReordersHomeAndTouchesNoOtherLane() {
        val reversed = HomeViews.Default.reversed().map(::ModuleSlot)
        val result = assertIs<ShellLayoutEditResult.Applied>(
            ShellLayoutEdits.apply(default, listOf(ShellLayoutOp.SetLane(ShellLanes.HOME, reversed))),
        )
        assertEquals(reversed, result.after.homeLane())
        assertEquals(listOf(ShellLanes.HOME), result.changes.map { it.lane })
        assertEquals(default.lanes - ShellLanes.HOME, result.after.lanes - ShellLanes.HOME)
    }

    @Test
    fun moveReordersWithinALane() {
        val after = applied(default, ShellLayoutOp.Move(ShellLanes.HOME, ModuleSlot(HomeViews.RECENTS), index = 0))
        assertEquals(HomeViews.RECENTS, after.homeLane().first().view)
        assertEquals(HomeViews.Default.size, after.homeLane().size)
    }

    @Test
    fun removeHidesAPanelPart() {
        val after = applied(default, ShellLayoutOp.Remove(ShellLanes.PANEL, ModuleSlot(PanelViews.NEW_CHAT)))
        assertEquals(PanelViews.Default - PanelViews.NEW_CHAT, after.panelLane().map { it.view })
    }

    @Test
    fun aCanvasCanBeAddedToTheSideAndSnappedFromMainToSide() {
        val inSide = applied(default, ShellLayoutOp.Add(ShellLanes.SIDE, notes, index = 0))
        assertEquals(listOf(notes, sidePane), inSide.sideLane())

        val inMain = applied(default, ShellLayoutOp.Add(ShellLanes.MAIN, notes))
        val snapped = applied(inMain, ShellLayoutOp.Move(ShellLanes.MAIN, notes, toLane = ShellLanes.SIDE))
        assertEquals(listOf(page), snapped.mainLane())
        assertEquals(listOf(sidePane, notes), snapped.sideLane())
    }

    @Test
    fun laterOpsSeeEarlierOnesAndResetStartsFromTheDefault() {
        val changed = applied(default, ShellLayoutOp.Remove(ShellLanes.RAIL, ModuleSlot(RailViews.NEW)))
        val after = applied(
            changed,
            ShellLayoutOp.Reset,
            ShellLayoutOp.Remove(ShellLanes.RAIL, ModuleSlot(RailViews.AGENTS)),
        )
        assertEquals(listOf(RailViews.HOME, RailViews.NEW), after.railLane().map { it.view })
        assertEquals(default.homeLane(), after.homeLane())
    }

    @Test
    fun anUnknownLaneIsRefused() {
        assertEquals(
            listOf(0 to ShellLayoutEditRule.UNKNOWN_LANE, 1 to ShellLayoutEditRule.UNKNOWN_LANE),
            rules(rejected(default, ShellLayoutOp.Add("dock", notes), ShellLayoutOp.Move(ShellLanes.MAIN, page, toLane = "dock"))),
        )
    }

    @Test
    fun eachLaneHoldsOnlyItsCatalogViews() {
        val errors = rejected(
            default,
            ShellLayoutOp.Add(ShellLanes.PANEL, ModuleSlot(HomeViews.RECENTS)),
            ShellLayoutOp.Add(ShellLanes.HOME, ModuleSlot(PanelViews.LIBRARY)),
            ShellLayoutOp.Add(ShellLanes.MAIN, sidePane),
            ShellLayoutOp.Add(ShellLanes.RAIL, ModuleSlot(RuntimeViewId("plugin.anything"))),
        )
        assertEquals((0..3).map { it to ShellLayoutEditRule.VIEW_NOT_ALLOWED }, rules(errors))
    }

    @Test
    fun aCanvasNeedsItsIdAndOtherViewsTakeNoObject() {
        val errors = rejected(
            default,
            ShellLayoutOp.Add(ShellLanes.SIDE, ModuleSlot(ShellViews.CANVAS)),
            ShellLayoutOp.Add(ShellLanes.SIDE, ModuleSlot(ShellViews.CANVAS, " ")),
            ShellLayoutOp.Remove(ShellLanes.HOME, ModuleSlot(HomeViews.STATS, "x")),
            ShellLayoutOp.Add(ShellLanes.SIDE, canvas("c".repeat(ShellLayoutCatalog.MAX_OBJECT_LENGTH + 1))),
        )
        assertEquals(
            listOf(
                0 to ShellLayoutEditRule.OBJECT_REQUIRED,
                1 to ShellLayoutEditRule.OBJECT_REQUIRED,
                2 to ShellLayoutEditRule.SLOT_NOT_FOUND,
                3 to ShellLayoutEditRule.OBJECT_TOO_LONG,
            ),
            rules(errors),
        )
        assertEquals(
            listOf(0 to ShellLayoutEditRule.OBJECT_NOT_ALLOWED),
            rules(rejected(default, ShellLayoutOp.Add(ShellLanes.SIDE, ModuleSlot(ShellViews.SIDE_PANE, "x")))),
        )
    }

    @Test
    fun thePageNeverLeavesTheMainLane() {
        val errors = rejected(
            default,
            ShellLayoutOp.Remove(ShellLanes.MAIN, page),
            ShellLayoutOp.Move(ShellLanes.MAIN, page, toLane = ShellLanes.SIDE),
            ShellLayoutOp.SetLane(ShellLanes.MAIN, listOf(notes)),
            ShellLayoutOp.SetLane(ShellLanes.SIDE, listOf(page)),
        )
        assertEquals(
            listOf(
                0 to ShellLayoutEditRule.PAGE_STAYS_IN_MAIN,
                1 to ShellLayoutEditRule.PAGE_STAYS_IN_MAIN,
                2 to ShellLayoutEditRule.PAGE_STAYS_IN_MAIN,
                3 to ShellLayoutEditRule.VIEW_NOT_ALLOWED,
                3 to ShellLayoutEditRule.DUPLICATE_SLOT,
            ),
            rules(errors),
        )
    }

    @Test
    fun settingsNeverLeavesTheSideMenu() {
        val settings = ModuleSlot(PanelViews.SETTINGS)
        val errors = rejected(
            default,
            ShellLayoutOp.Remove(ShellLanes.PANEL, settings),
            ShellLayoutOp.Move(ShellLanes.PANEL, settings, toLane = ShellLanes.RAIL),
            ShellLayoutOp.SetLane(ShellLanes.PANEL, listOf(ModuleSlot(PanelViews.LIBRARY))),
        )
        assertEquals((0..2).map { it to ShellLayoutEditRule.SETTINGS_STAYS }, rules(errors))
        assertEquals(PanelViews.SETTINGS, applied(default, ShellLayoutOp.Move(ShellLanes.PANEL, settings, index = 0)).panelLane()[0].view)
    }

    @Test
    fun homeNeverLeavesTheRail() {
        val home = ModuleSlot(RailViews.HOME)
        val errors = rejected(
            default,
            ShellLayoutOp.Remove(ShellLanes.RAIL, home),
            ShellLayoutOp.Move(ShellLanes.RAIL, home, toLane = ShellLanes.PANEL),
            ShellLayoutOp.SetLane(ShellLanes.RAIL, listOf(ModuleSlot(RailViews.AGENTS), ModuleSlot(RailViews.NEW))),
        )
        assertEquals((0..2).map { it to ShellLayoutEditRule.HOME_STAYS }, rules(errors))
        assertEquals("${RailViews.HOME.value} must stay in lane ${ShellLanes.RAIL}.", errors.first().detail)
        val reordered = applied(default, ShellLayoutOp.Move(ShellLanes.RAIL, home, index = RailViews.Default.size - 1))
        assertEquals(listOf(RailViews.AGENTS, RailViews.NEW, RailViews.HOME), reordered.railLane().map { it.view })
    }

    @Test
    fun anEditThatTouchesARailWithoutHomeMustPutHomeBack() {
        val noHome = default.copy(lanes = default.lanes + (ShellLanes.RAIL to listOf(ModuleSlot(RailViews.AGENTS))))
        val errors = rejected(noHome, ShellLayoutOp.Add(ShellLanes.RAIL, ModuleSlot(RailViews.NEW)))
        assertEquals(listOf(null to ShellLayoutEditRule.HOME_STAYS), rules(errors))
        // A lane the edit does not touch is not checked, and putting Home back is allowed.
        applied(noHome, ShellLayoutOp.Remove(ShellLanes.PANEL, ModuleSlot(PanelViews.NEW_CHAT)))
        val restored = applied(noHome, ShellLayoutOp.Add(ShellLanes.RAIL, ModuleSlot(RailViews.HOME), index = 0))
        assertEquals(listOf(RailViews.HOME, RailViews.AGENTS), restored.railLane().map { it.view })
    }

    @Test
    fun aSlotAppearsOnceAndInOnlyOneOfMainAndSide() {
        val withNotesInMain = applied(default, ShellLayoutOp.Add(ShellLanes.MAIN, notes))
        val errors = rejected(
            withNotesInMain,
            ShellLayoutOp.Add(ShellLanes.HOME, ModuleSlot(HomeViews.RECENTS)),
            ShellLayoutOp.SetLane(ShellLanes.RAIL, listOf(ModuleSlot(RailViews.HOME), ModuleSlot(RailViews.HOME))),
            ShellLayoutOp.Add(ShellLanes.SIDE, notes),
            ShellLayoutOp.SetLane(ShellLanes.SIDE, listOf(notes)),
        )
        assertEquals((0..3).map { it to ShellLayoutEditRule.DUPLICATE_SLOT }, rules(errors))
    }

    @Test
    fun aLaneCannotGrowPastItsCap() {
        val errors = rejected(
            default,
            ShellLayoutOp.Add(ShellLanes.SIDE, canvas("a")),
            ShellLayoutOp.Add(ShellLanes.SIDE, canvas("b")),
            ShellLayoutOp.Add(ShellLanes.SIDE, canvas("c")),
        )
        assertEquals(listOf(null to ShellLayoutEditRule.LANE_FULL), rules(errors))
        // A lane briefly over its cap inside one edit is fine when the edit ends within it.
        applied(default, ShellLayoutOp.Add(ShellLanes.SIDE, canvas("a")), ShellLayoutOp.Add(ShellLanes.SIDE, canvas("b")),
            ShellLayoutOp.Add(ShellLanes.SIDE, canvas("c")), ShellLayoutOp.Remove(ShellLanes.SIDE, sidePane))
    }

    @Test
    fun anInvalidOpAppliesNothingAndEveryProblemIsReported() {
        val ops = listOf(
            ShellLayoutOp.Remove(ShellLanes.PANEL, ModuleSlot(PanelViews.NEW_CHAT)),
            ShellLayoutOp.Remove(ShellLanes.MAIN, page),
            ShellLayoutOp.Move(ShellLanes.HOME, ModuleSlot(HomeViews.RECENTS), index = 99),
            ShellLayoutOp.Remove(ShellLanes.RAIL, ModuleSlot(RailViews.NEW)),
        )
        val errors = assertIs<ShellLayoutEditResult.Rejected>(ShellLayoutEdits.apply(default, ops)).errors
        assertEquals(listOf(1 to ShellLayoutEditRule.PAGE_STAYS_IN_MAIN, 2 to ShellLayoutEditRule.INDEX_OUT_OF_RANGE), rules(errors))
        assertTrue(errors.all { it.detail.isNotBlank() })
    }

    @Test
    fun anEditCarriesBetweenOneAndTheMostOps() {
        assertEquals(listOf(null to ShellLayoutEditRule.OP_COUNT), rules(rejected(default)))
        val tooMany = List(ShellLayoutEdits.MAX_OPS + 1) { ShellLayoutOp.Reset }
        assertEquals(listOf(null to ShellLayoutEditRule.OP_COUNT), rules(rejected(default, *tooMany.toTypedArray())))
    }

    @Test
    fun theCatalogDescribesEveryLaneItGuards() {
        assertEquals(
            listOf(ShellLanes.MAIN, ShellLanes.SIDE, ShellLanes.HOME, ShellLanes.RAIL, ShellLanes.PANEL),
            ShellLayoutCatalog.lanes.map { it.lane },
        )
        assertTrue(ShellLayoutCatalog.lanes.all { lane -> lane.description.isNotBlank() && lane.views.all { it.description.isNotBlank() } })
        assertEquals(HomeViews.Default, ShellLayoutCatalog.lane(ShellLanes.HOME)!!.views.map { it.view })
        assertEquals(PanelViews.Default, ShellLayoutCatalog.lane(ShellLanes.PANEL)!!.views.map { it.view })
        assertEquals(RailViews.Default, ShellLayoutCatalog.lane(ShellLanes.RAIL)!!.views.map { it.view })
    }
}
