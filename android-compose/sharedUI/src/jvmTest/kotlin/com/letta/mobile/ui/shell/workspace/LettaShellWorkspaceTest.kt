@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.AgentIdentityPillTestTags
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.shell.LocalShellFloatingChromeInset
import com.letta.mobile.ui.shell.header.ShellHeaderDefaults
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PageTag = "workspace-page"

private val Navigation = ShellWorkspaceNavigation(
    rail = ShellLibraryRailState(
        agents = listOf("agent-a" to "Ada", "agent-b" to "Bo"),
        focus = ShellRailFocus(selectedAgentId = "agent-a"),
    ),
    panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
)

private val OpenTabs = ShellConversationTabs(tabs = listOf(LettaTab(key = "conv-1", title = "Open chat")), activeKey = "conv-1")

private fun workspaceState(
    title: String = "Window title",
    forceCompact: Boolean = false,
    tabs: ShellConversationTabs = ShellConversationTabs(),
    navigation: ShellWorkspaceNavigation = Navigation,
) = LettaShellWorkspaceState(
    header = ShellWorkspaceHeaderState(title = title, sidebar = ShellSidebarControl(collapsed = false, onToggle = {}), tabs = tabs),
    navigation = navigation,
    layout = ShellWorkspaceLayout(forceCompact = forceCompact),
)

private fun workspaceHost(
    chrome: ShellWindowChrome = ShellWindowChrome.Inline,
    drawer: ShellWorkspaceDrawer = ShellWorkspaceDrawer(),
    actions: ShellWorkspaceNavigationActions = ShellWorkspaceNavigationActions(),
    main: @Composable (Modifier) -> Unit = { modifier -> Text("The page", modifier.testTag(PageTag)) },
) = LettaShellWorkspaceHost(
    actions = actions,
    slots = LettaShellWorkspaceSlots(
        main = main,
        agentCard = {},
        sidePane = {},
        overlays = {},
    ),
    chrome = chrome,
    drawer = drawer,
    events = ShellWorkspaceEvents(),
)

/** letta-mobile-ckmub.17.4: the one workspace desktop and web mount, wide and compact. */
class LettaShellWorkspaceTest {
    private fun sized(width: Dp) = Modifier.size(width = width, height = 800.dp)

    @Test
    fun wideWindowShowsRailPanelAndPageWithTheHeaderInline() = runComposeUiTest {
        setContent { LettaShellWorkspace(workspaceState(), workspaceHost(), sized(1440.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.WIDE).assertExists()
        onNodeWithTag(PageTag).assertExists()
        onNodeWithText("Window title").assertExists()
        onNodeWithTag(ShellWorkspaceHeaderTags.SIDEBAR_TOGGLE).assertExists()
    }

    /**
     * #2096 regression: the inline header's free width fills its height, so an unbounded header
     * took the whole browser window, drew its row centred in it and left the rail, panel and page
     * no height at all. The header keeps its bar's height and the body takes the rest.
     */
    @Test
    fun inlineHeaderKeepsItsBarHeightAndTheBodyTakesTheRest() = runComposeUiTest {
        setContent { LettaShellWorkspace(workspaceState(), workspaceHost(), sized(1440.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.INLINE_HEADER).assertHeightIsEqualTo(LettaDimens.Control.minTouchTarget)
        onNodeWithTag(LettaShellWorkspaceTags.INLINE_HEADER).assertTopPositionInRootIsEqualTo(0.dp)
        onNodeWithTag(PageTag).assertIsDisplayed()
        onNodeWithTag(LettaShellWorkspaceTags.WIDE).assertHeightIsAtLeast(700.dp)
    }

    /**
     * The phone has no header bar: the page runs up to the top edge, the minimal header's controls
     * float over it in the phone app bar's height, and the page is told the band they float over.
     */
    @Test
    fun aCompactInlineHeaderFloatsOverThePageWhichStartsAtTheTop() = runComposeUiTest {
        var inset: Dp? = null
        val host = workspaceHost(main = { modifier ->
            inset = LocalShellFloatingChromeInset.current
            Text("The page", modifier.testTag(PageTag))
        })
        setContent { LettaShellWorkspace(workspaceState(), host, sized(400.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.INLINE_HEADER).assertDoesNotExist()
        onNodeWithTag(PageTag).assertTopPositionInRootIsEqualTo(0.dp)
        onNodeWithTag(LettaShellWorkspaceTags.FLOATING_HEADER)
            .assertTopPositionInRootIsEqualTo(0.dp)
            .assertHeightIsEqualTo(ShellHeaderDefaults.TouchMinHeight)
        val page = onNodeWithTag(PageTag).getUnclippedBoundsInRoot()
        val menu = onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).getUnclippedBoundsInRoot()
        assertTrue(menu.top >= page.top && menu.bottom <= ShellHeaderDefaults.TouchMinHeight, "menu $menu is not over the page $page")
        runOnIdle { assertEquals(ShellHeaderDefaults.TouchMinHeight, inset) }
    }

    @Test
    fun theFloatingBandPassesTouchesToThePageWhileItsControlsKeepTheirs() = runComposeUiTest {
        var pageTaps = 0
        val drawer = ShellWorkspaceDrawer()
        val host = workspaceHost(drawer = drawer, main = { modifier ->
            Box(modifier.testTag(PageTag).clickable { pageTaps++ })
        })
        setContent { LettaShellWorkspace(workspaceState(), host, sized(400.dp)) }

        // Between the pill and the search, in the band: the page takes it.
        onNodeWithTag(PageTag).performTouchInput { click(Offset(width * 0.55f, ShellHeaderDefaults.TouchMinHeight.toPx() / 2)) }
        runOnIdle { assertEquals(1, pageTaps) }
        onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).performClick()
        runOnIdle {
            assertTrue(drawer.open)
            assertEquals(1, pageTaps)
        }
    }

    @Test
    fun aWideWindowsPageHasNoFloatingChrome() = runComposeUiTest {
        var inset: Dp? = null
        val host = workspaceHost(main = { modifier ->
            inset = LocalShellFloatingChromeInset.current
            Text("The page", modifier.testTag(PageTag))
        })
        setContent { LettaShellWorkspace(workspaceState(), host, sized(1440.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.FLOATING_HEADER).assertDoesNotExist()
        onNodeWithTag(LettaShellWorkspaceTags.INLINE_HEADER).assertExists()
        runOnIdle { assertEquals(0.dp, inset) }
    }

    @Test
    fun aCompactHoistedHeaderFloatsNothingOverThePage() = runComposeUiTest {
        var inset: Dp? = null
        val host = workspaceHost(chrome = ShellWindowChrome.Hoisted {}, main = { modifier ->
            inset = LocalShellFloatingChromeInset.current
            Text("The page", modifier.testTag(PageTag))
        })
        setContent { LettaShellWorkspace(workspaceState(), host, sized(400.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.FLOATING_HEADER).assertDoesNotExist()
        runOnIdle { assertEquals(0.dp, inset) }
    }

    @Test
    fun theChatPlatformTakesTheFloatingInsetUnlessItsOwnIsLarger() {
        assertEquals(64.dp, ChatSurfacePlatform().underFloatingChrome(64.dp).topChromeInset)
        assertEquals(80.dp, ChatSurfacePlatform(topChromeInset = 80.dp).underFloatingChrome(64.dp).topChromeInset)
        assertEquals(0.dp, ChatSurfacePlatform().underFloatingChrome(0.dp).topChromeInset)
    }

    @Test
    fun narrowWindowFoldsTheNavigationIntoTheDrawerTheHeaderOpens() = runComposeUiTest {
        val drawer = ShellWorkspaceDrawer()
        setContent { LettaShellWorkspace(workspaceState(), workspaceHost(drawer = drawer), sized(500.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.COMPACT).assertExists()
        onNodeWithTag(LettaShellWorkspaceTags.WIDE).assertDoesNotExist()
        onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).performClick()
        waitForIdle()
        assertTrue(drawer.open)
    }

    @Test
    fun forcedCompactFoldsAWideWindowToo() = runComposeUiTest {
        setContent { LettaShellWorkspace(workspaceState(forceCompact = true), workspaceHost(), sized(1440.dp)) }

        onNodeWithTag(LettaShellWorkspaceTags.COMPACT).assertExists()
    }

    @Test
    fun hoistedChromeHandsTheHeaderToTheHostInsteadOfDrawingIt() = runComposeUiTest {
        var hoisted: ShellWorkspaceHeaderState? = null
        setContent {
            val host = workspaceHost(chrome = ShellWindowChrome.Hoisted { hoisted = it })
            LettaShellWorkspace(workspaceState(title = "Hoisted title"), host, sized(1440.dp))
        }

        waitForIdle()
        assertEquals("Hoisted title", hoisted?.title)
        onNodeWithText("Hoisted title").assertDoesNotExist()
        onNodeWithTag(PageTag).assertExists()
    }

    @Test
    fun aCompactHoistedHeaderOpensTheDrawer() = runComposeUiTest {
        var hoisted: ShellWorkspaceHeaderState? = null
        val drawer = ShellWorkspaceDrawer()
        setContent {
            LettaShellWorkspace(workspaceState(), workspaceHost(ShellWindowChrome.Hoisted { hoisted = it }, drawer), sized(500.dp))
        }

        waitForIdle()
        val minimal = hoisted?.minimal
        // A small desktop window's title bar gets the minimal header too: no tabs, no sidebar control.
        assertEquals("Ada", minimal?.identity?.name)
        assertNull(hoisted?.sidebar)
        minimal?.onOpenNavigation?.invoke()
        assertTrue(drawer.open)
    }

    @Test
    fun aCompactHeaderIsTheAgentPillTheSearchAndTheMenuWithNoTabStrip() = runComposeUiTest {
        setContent { LettaShellWorkspace(workspaceState(tabs = OpenTabs), workspaceHost(), sized(400.dp)) }

        onNodeWithTag(AgentIdentityPillTestTags.PILL).assertIsDisplayed()
        onNode(hasTestTag(AgentIdentityPillTestTags.TRIGGER) and hasText("Ada")).assertIsDisplayed()
        onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).assertIsDisplayed()
        onNodeWithText("Open chat").assertDoesNotExist()
        onNodeWithTag(ShellWorkspaceHeaderTags.SIDEBAR_TOGGLE).assertDoesNotExist()
        // The menu sits on the right, after the pill.
        val pill = onNodeWithTag(AgentIdentityPillTestTags.PILL).getUnclippedBoundsInRoot()
        val menu = onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).getUnclippedBoundsInRoot()
        assertTrue(menu.left >= pill.right, "menu $menu is not right of the pill $pill")
    }

    @Test
    fun aWideHeaderKeepsTheTabStripAndTheSidebarControl() = runComposeUiTest {
        setContent { LettaShellWorkspace(workspaceState(tabs = OpenTabs), workspaceHost(), sized(1440.dp)) }

        onNodeWithText("Open chat").assertExists()
        onNodeWithTag(ShellWorkspaceHeaderTags.SIDEBAR_TOGGLE).assertExists()
        onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).assertDoesNotExist()
        onNodeWithTag(AgentIdentityPillTestTags.PILL).assertDoesNotExist()
    }

    @Test
    fun thePillOpensTheDrawerOnTapAndPinsOnLongPress() = runComposeUiTest {
        val drawer = ShellWorkspaceDrawer()
        val pins = mutableListOf<Pair<String, Boolean>>()
        val actions = ShellWorkspaceNavigationActions(
            rail = ShellAgentRailActions(onAgentPinnedChange = { id, pinned -> pins += id to pinned }),
        )
        setContent { LettaShellWorkspace(workspaceState(), workspaceHost(drawer = drawer, actions = actions), sized(400.dp)) }

        onNodeWithTag(AgentIdentityPillTestTags.TRIGGER).performTouchInput { longClick() }
        runOnIdle { assertEquals(listOf("agent-a" to true), pins) }
        onNodeWithTag(AgentIdentityPillTestTags.TRIGGER).performClick()
        runOnIdle { assertTrue(drawer.open) }
    }

    @Test
    fun aPinnedAgentsLongPressUnpinsIt() {
        val pinned = Navigation.copy(rail = Navigation.rail.copy(focus = Navigation.rail.focus.copy(pinnedAgentIds = setOf("agent-a"))))
        val pins = mutableListOf<Pair<String, Boolean>>()
        val actions = ShellAgentRailActions(onAgentPinnedChange = { id, p -> pins += id to p })
        val identity = checkNotNull(pinned.focusedIdentity(actions, onSwitch = {}))

        assertTrue(identity.isPinned)
        identity.onLongClick()
        assertEquals(listOf("agent-a" to false), pins)
    }

    @Test
    fun withoutAFocusedAgentTheMinimalHeaderShowsTheTitle() = runComposeUiTest {
        val unfocused = Navigation.copy(rail = Navigation.rail.copy(focus = ShellRailFocus()))
        setContent { LettaShellWorkspace(workspaceState(navigation = unfocused), workspaceHost(), sized(400.dp)) }

        onNodeWithTag(AgentIdentityPillTestTags.PILL).assertDoesNotExist()
        onNodeWithText("Window title").assertExists()
        onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).assertExists()
    }
}
