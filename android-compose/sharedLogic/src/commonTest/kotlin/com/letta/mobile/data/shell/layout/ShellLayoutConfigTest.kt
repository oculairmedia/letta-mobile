package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.model.view.RuntimeViewId
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellLayoutConfigTest {
    private val notes = ModuleSlot(RuntimeViewId("canvas"), objectRef = "project-notes")

    @Test
    fun theDefaultIsThePageWithTheSidePaneAfterIt() {
        assertEquals(listOf(ModuleSlot(ShellViews.PAGE)), ShellLayoutConfig.Default.mainLane())
        assertEquals(listOf(ModuleSlot(ShellViews.SIDE_PANE)), ShellLayoutConfig.Default.lane(ShellLanes.SIDE))
    }

    @Test
    fun aConfigSurvivesSavingAndReading() {
        val config = ShellLayoutConfig.Default.copy(lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.SIDE to listOf(notes)))

        assertEquals(config, ShellLayoutConfig.decode(config.encode()))
    }

    @Test
    fun anOlderConfigGainsTheDefaultsItLacksAndKeepsItsOwnModules() {
        val old = ShellLayoutConfig(version = 0, lanes = mapOf(ShellLanes.MAIN to listOf(notes)))

        val upgraded = old.upgraded()

        assertEquals(listOf(notes, ModuleSlot(ShellViews.PAGE)), upgraded.lane(ShellLanes.MAIN))
        assertEquals(listOf(ModuleSlot(ShellViews.SIDE_PANE)), upgraded.lane(ShellLanes.SIDE))
        assertEquals(ShellLayoutConfig.VERSION, upgraded.version)
    }

    @Test
    fun aModuleRemovedFromACurrentConfigStaysRemoved() {
        val withoutSidePane = ShellLayoutConfig.Default.copy(lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.SIDE to emptyList()))

        assertEquals(emptyList(), ShellLayoutConfig.decode(withoutSidePane.encode()).lane(ShellLanes.SIDE))
    }

    @Test
    fun anEmptiedMainLaneStillShowsThePage() {
        val emptied = ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to emptyList()))

        assertEquals(listOf(ModuleSlot(ShellViews.PAGE)), emptied.mainLane())
    }

    @Test
    fun anUnreadableConfigIsTheDefault() {
        assertEquals(ShellLayoutConfig.Default, ShellLayoutConfig.decode("not json"))
        assertEquals(ShellLayoutConfig.Default, ShellLayoutConfig.decode("""{"version":1,"lanes":{"main":[{"view":""}]}}"""))
    }

    @Test
    fun aNarrowWindowKeepsThePageBeforeAnyOtherModule() {
        val files = ModuleSlot(RuntimeViewId("files"))
        val config = ShellLayoutConfig(
            version = ShellLayoutConfig.VERSION,
            lanes = mapOf(ShellLanes.MAIN to listOf(notes, ModuleSlot(ShellViews.PAGE), files)),
        )

        assertEquals(listOf(ModuleSlot(ShellViews.PAGE)), config.mainLane(room = 1))
        assertEquals(listOf(notes, ModuleSlot(ShellViews.PAGE)), config.mainLane(room = 2))
        assertEquals(config.mainLane(), config.mainLane(room = 3))
        assertEquals(listOf(ModuleSlot(ShellViews.PAGE)), config.mainLane(room = 0))
    }

    @Test
    fun aModuleInBothLanesIsDrawnInTheMainLaneOnly() {
        val both = ShellLayoutConfig(
            version = ShellLayoutConfig.VERSION,
            lanes = mapOf(ShellLanes.MAIN to listOf(notes), ShellLanes.SIDE to listOf(notes, ModuleSlot(ShellViews.SIDE_PANE))),
        )

        assertEquals(listOf(ModuleSlot(ShellViews.SIDE_PANE)), both.sideLane())
    }

    @Test
    fun aRepeatedModuleIsDrawnOnce() {
        val repeated = ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.SIDE to listOf(notes, notes)))

        assertEquals(listOf(notes), repeated.lane(ShellLanes.SIDE))
    }
}
