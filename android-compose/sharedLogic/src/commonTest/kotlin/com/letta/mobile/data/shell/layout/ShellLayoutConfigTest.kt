package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews
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

    private val homeDefaults = listOf(
        HomeViews.COMPOSER,
        HomeViews.NOW,
        HomeViews.RECENTS,
        HomeViews.AGENTS,
        HomeViews.STATS,
        HomeViews.PINNED,
        HomeViews.FLEET,
    ).map { ModuleSlot(it) }

    @Test
    fun theDefaultHomeLaneIsTheWidePageOrder() {
        assertEquals(homeDefaults, ShellLayoutConfig.Default.lane(ShellLanes.HOME))
        assertEquals(
            listOf("home.composer", "home.now", "home.recents", "home.agents", "home.stats", "home.pinned", "home.fleet"),
            homeDefaults.map { it.view.value },
        )
    }

    @Test
    fun aVersionOneConfigWithoutAHomeLaneGainsItOnce() {
        val v1 = ShellLayoutConfig(
            version = 1,
            lanes = mapOf(
                ShellLanes.MAIN to listOf(ModuleSlot(ShellViews.PAGE)),
                ShellLanes.SIDE to listOf(ModuleSlot(ShellViews.SIDE_PANE)),
            ),
        )

        val upgraded = ShellLayoutConfig.decode(v1.encode())

        assertEquals(ShellLayoutConfig.VERSION, upgraded.version)
        assertEquals(homeDefaults, upgraded.lane(ShellLanes.HOME))
        assertEquals(ShellLayoutConfig.Default, upgraded)

        // The person then removes the stats strip; reading it back at the same version keeps it removed.
        val noStats = homeDefaults - ModuleSlot(HomeViews.STATS)
        val withoutStats = upgraded.copy(lanes = upgraded.lanes + (ShellLanes.HOME to noStats))
        val reread = ShellLayoutConfig.decode(withoutStats.encode())

        assertEquals(noStats, reread.lane(ShellLanes.HOME))
        assertEquals(reread, reread.upgraded())
    }

    @Test
    fun upgradingAVersionOneConfigDoesNotBringBackItsRemovedModules() {
        val v1WithoutSidePane = ShellLayoutConfig(
            version = 1,
            lanes = mapOf(ShellLanes.MAIN to listOf(ModuleSlot(ShellViews.PAGE)), ShellLanes.SIDE to emptyList()),
        )

        val upgraded = v1WithoutSidePane.upgraded()

        assertEquals(emptyList(), upgraded.lane(ShellLanes.SIDE))
        assertEquals(homeDefaults, upgraded.lane(ShellLanes.HOME))
    }

    @Test
    fun anUpgradeKeepsAHomeLaneTheConfigAlreadyHas() {
        val own = listOf(ModuleSlot(HomeViews.RECENTS), notes)
        val v1 = ShellLayoutConfig(version = 1, lanes = mapOf(ShellLanes.HOME to own))

        assertEquals(own + (homeDefaults - ModuleSlot(HomeViews.RECENTS)), v1.upgraded().lane(ShellLanes.HOME))
        assertEquals(emptyList(), v1.upgraded().lane(ShellLanes.SIDE))
    }

    @Test
    fun aConfigWithoutAHomeLaneShowsTheDefaultSectionsAndAnEmptiedOneShowsNone() {
        val noHomeLane = ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to listOf(notes)))
        val emptied = ShellLayoutConfig.Default.copy(lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.HOME to emptyList()))

        assertEquals(homeDefaults, noHomeLane.homeLane())
        assertEquals(emptyList(), emptied.homeLane())
    }

    private val railDefaults = listOf(RailViews.HOME, RailViews.AGENTS, RailViews.NEW).map { ModuleSlot(it) }
    private val panelDefaults = listOf(
        PanelViews.HEADER,
        PanelViews.SECTIONS,
        PanelViews.NEW_CHAT,
        PanelViews.LIBRARY,
        PanelViews.SETTINGS,
    ).map { ModuleSlot(it) }

    @Test
    fun theDefaultRailAndPanelLanesAreTodaysOrder() {
        assertEquals(railDefaults, ShellLayoutConfig.Default.lane(ShellLanes.RAIL))
        assertEquals(panelDefaults, ShellLayoutConfig.Default.lane(ShellLanes.PANEL))
        assertEquals(listOf("rail.home", "rail.agents", "rail.new"), railDefaults.map { it.view.value })
        assertEquals(
            listOf("panel.header", "panel.sections", "panel.newChat", "panel.library", "panel.settings"),
            panelDefaults.map { it.view.value },
        )
    }

    @Test
    fun aVersionTwoConfigGainsTheRailAndPanelLanesOnce() {
        val v2 = ShellLayoutConfig(
            version = 2,
            lanes = mapOf(
                ShellLanes.MAIN to listOf(ModuleSlot(ShellViews.PAGE)),
                ShellLanes.SIDE to listOf(ModuleSlot(ShellViews.SIDE_PANE)),
                ShellLanes.HOME to homeDefaults,
            ),
        )

        val upgraded = ShellLayoutConfig.decode(v2.encode())

        assertEquals(ShellLayoutConfig.Default, upgraded)
        assertEquals(upgraded, ShellLayoutConfig.decode(upgraded.encode()))
        assertEquals(railDefaults, upgraded.upgraded().lane(ShellLanes.RAIL))
        assertEquals(panelDefaults, upgraded.upgraded().lanes.getValue(ShellLanes.PANEL))
    }

    @Test
    fun aRailOrPanelPartRemovedAfterTheUpgradeStaysRemoved() {
        val upgraded = ShellLayoutConfig(version = 2).upgraded()
        val noNewChat = panelDefaults - ModuleSlot(PanelViews.NEW_CHAT)
        val noHome = railDefaults - ModuleSlot(RailViews.HOME)
        val edited = upgraded.copy(lanes = upgraded.lanes + (ShellLanes.PANEL to noNewChat) + (ShellLanes.RAIL to noHome))

        val reread = ShellLayoutConfig.decode(edited.encode())

        assertEquals(noNewChat, reread.panelLane())
        assertEquals(noHome, reread.railLane())
    }

    @Test
    fun earlierRemovalsStayRemovedWhenTheRailAndPanelArrive() {
        val noStats = homeDefaults - ModuleSlot(HomeViews.STATS)
        val v2 = ShellLayoutConfig(
            version = 2,
            lanes = mapOf(
                ShellLanes.MAIN to listOf(ModuleSlot(ShellViews.PAGE)),
                ShellLanes.SIDE to emptyList(),
                ShellLanes.HOME to noStats,
            ),
        )

        val upgraded = ShellLayoutConfig.decode(v2.encode())

        assertEquals(emptyList(), upgraded.lane(ShellLanes.SIDE))
        assertEquals(noStats, upgraded.homeLane())
        assertEquals(railDefaults, upgraded.railLane())
        assertEquals(panelDefaults, upgraded.panelLane())
    }

    @Test
    fun aConfigWithoutRailOrPanelLanesShowsTheirDefaultsAndEmptiedOnesShowNone() {
        val noLanes = ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to listOf(notes)))
        val emptied = ShellLayoutConfig.Default.copy(
            lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.RAIL to emptyList()) + (ShellLanes.PANEL to emptyList()),
        )

        assertEquals(railDefaults, noLanes.railLane())
        assertEquals(panelDefaults, noLanes.panelLane())
        assertEquals(emptyList(), emptied.railLane())
        assertEquals(emptyList(), emptied.panelLane())
    }
}
