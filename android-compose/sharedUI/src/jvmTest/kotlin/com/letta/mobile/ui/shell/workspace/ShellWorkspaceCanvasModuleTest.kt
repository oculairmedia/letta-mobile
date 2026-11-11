@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import com.letta.mobile.data.chat.layout.ChatViews
import com.letta.mobile.ui.chat.surface.ChatLane
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.canvas.boards.CanvasBoardSource
import com.letta.mobile.ui.canvas.boards.LocalCanvasBoardSource
import com.letta.mobile.ui.canvas.boards.ProvideCanvasBoardSource
import com.letta.mobile.ui.canvas.boards.canvasBoardSource
import com.letta.mobile.ui.shell.modules.ShellModuleTags
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private const val Page = "canvas-module-page"
private const val HostPane = "canvas-module-host-pane"
private const val NotesId = "canvas-project-notes"
private const val PlanId = "canvas-release-plan"
private const val NotesTitle = "Project notes"
private const val PlanTitle = "Release plan"
private const val TimeoutMillis = 10_000L
private val HostPaneWidth = 100.dp
private val WindowHeight = 800.dp
private val PageSlot = ModuleSlot(ShellViews.PAGE)
private val HostPaneSlot = ModuleSlot(ShellViews.SIDE_PANE)
private val NotesSlot = ModuleSlot(ShellViews.CANVAS, NotesId)
private val PlanSlot = ModuleSlot(ShellViews.CANVAS, PlanId)

/**
 * A canvas board is a module like any other: a layout that places one shows that board in its lane
 * beside the page, each slot its own board, at the size the lane gives it. The default layout has
 * none and draws the shell as it was; a compact window keeps the page and leaves side modules out;
 * a board no store holds, or a host that cannot open boards, shows the shell's unavailable module.
 */
class ShellWorkspaceCanvasModuleTest {
    private val store = InMemoryCanvasDocumentStore()

    init {
        runBlocking {
            listOf(NotesId to NotesTitle, PlanId to PlanTitle).forEach { (id, title) ->
                CanvasSession.create(store, CanvasCreateOptions(title = title, canvasId = CanvasId(id)))
            }
        }
    }

    /** The ids the source was asked to open, in order. */
    private val opened = mutableListOf<String>()

    private val source = CanvasBoardSource { id ->
        opened += id.value
        CanvasSession.open(store, id)
    }

    private fun state(lanes: ShellLayoutConfig) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
        ),
        layout = ShellWorkspaceLayout(lanes = lanes),
    )

    private fun host(page: @Composable (Modifier) -> Unit = { modifier -> Box(modifier.testTag(Page)) }) = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        slots = LettaShellWorkspaceSlots(
            main = page,
            agentCard = {},
            sidePane = { Box(Modifier.width(HostPaneWidth).fillMaxHeight().testTag(HostPane)) },
            overlays = {},
        ),
        chrome = ShellWindowChrome.Hoisted {},
        drawer = ShellWorkspaceDrawer(),
        events = ShellWorkspaceEvents(),
    )

    private fun lanes(main: List<ModuleSlot> = listOf(PageSlot), side: List<ModuleSlot> = listOf(HostPaneSlot)) =
        ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(ShellLanes.MAIN to main, ShellLanes.SIDE to side))

    /** A window exactly [width] wide showing the workspace with [config], boards opened through [boards]. */
    private fun window(
        width: Dp,
        config: ShellLayoutConfig,
        boards: CanvasBoardSource? = source,
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
        setContent {
            CompositionLocalProvider(LocalCanvasBoardSource provides boards) {
                LettaShellWorkspace(state(config), host(), Modifier.size(width = width, height = WindowHeight))
            }
        }
        block()
    }

    private fun boardTag(id: String) = shellCanvasModuleTag(CanvasId(id))

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.awaitBoard(id: String) =
        waitUntil(timeoutMillis = TimeoutMillis) { onAllNodesWithTag(boardTag(id)).fetchSemanticsNodes().isNotEmpty() }

    /** The board [id]'s module shows [title], the title of that board and no other. */
    private fun ComposeUiTest.assertBoardTitled(id: String, title: String) {
        val inModule = hasAnyAncestor(hasTestTag(boardTag(id)))
        waitUntil(timeoutMillis = TimeoutMillis) {
            onAllNodes(hasText(title, substring = true) and inModule).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertDp(expected: Dp, actual: Dp, what: String) =
        assertEquals(expected.value, actual.value, absoluteTolerance = 1f, message = what)

    private fun ComposeUiTest.assertNoCanvasModule() {
        listOf(NotesId, PlanId).forEach { onNodeWithTag(boardTag(it)).assertDoesNotExist() }
        onNodeWithTag(SHELL_CANVAS_MODULE_OPENING_TAG).assertDoesNotExist()
    }

    private fun theDefaultHasNoCanvas(width: Dp) = window(width, ShellLayoutConfig.Default) {
        waitForIdle()
        assertDp(width - HostPaneWidth, bounds(Page).right, "the page ends where the host pane starts at $width")
        assertDp(width, bounds(HostPane).right, "the host pane ends at the window edge at $width")
        assertNoCanvasModule()
        assertEquals(emptyList(), opened, "the default layout opens no board at $width")
    }

    @Test
    fun chatLaneMountsTheRegisteredBoardAboveItsTranscript() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val config = ShellLayoutConfig.Default.copy(
            lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.CHAT to (listOf(NotesSlot) + ChatViews.Default.map(::ModuleSlot))),
        )
        setContent {
            CompositionLocalProvider(LocalCanvasBoardSource provides source) {
                LettaShellWorkspace(
                    state(config),
                    host { modifier ->
                        ChatLane(
                            modifier = modifier,
                            transcript = { Box(it.testTag(Page)) },
                            status = {},
                            composer = { inputModifier, _ -> Box(inputModifier.height(48.dp)) },
                        )
                    },
                    Modifier.size(1200.dp, WindowHeight),
                )
            }
        }
        awaitBoard(NotesId)
        assertBoardTitled(NotesId, NotesTitle)
        assertEquals(listOf(NotesId), opened)
        assertDp(bounds(boardTag(NotesId)).bottom, bounds(Page).top, "board is above the protected transcript")
    }

    @Test
    fun theDefaultLayoutHasNoCanvasOnAPhone() = theDefaultHasNoCanvas(360.dp)

    @Test
    fun theDefaultLayoutHasNoCanvasJustBelowTheWideBreakpoint() = theDefaultHasNoCanvas(700.dp)

    @Test
    fun theDefaultLayoutHasNoCanvasJustAboveTheWideBreakpoint() = theDefaultHasNoCanvas(760.dp)

    @Test
    fun theDefaultLayoutHasNoCanvasOnADesktop() = theDefaultHasNoCanvas(1200.dp)

    private fun aSideCanvasSitsAfterThePage(width: Dp) = window(width, lanes(side = listOf(NotesSlot))) {
        awaitBoard(NotesId)
        assertBoardTitled(NotesId, NotesTitle)
        val board = bounds(boardTag(NotesId))
        assertDp(LettaDimens.Pane.sidePanelWidth, board.width, "the board is a side panel wide at $width")
        assertDp(width, board.right, "it ends at the window edge at $width")
        assertDp(WindowHeight, board.bottom, "it runs to the bottom at $width")
        assertEquals(true, bounds(Page).right <= board.left, "the page ends before it at $width")
        onNodeWithTag(Page).assertIsDisplayed()
    }

    @Test
    fun aCanvasSnappedToTheSideSitsAfterThePageOnADesktop() = aSideCanvasSitsAfterThePage(1200.dp)

    @Test
    fun aCanvasSnappedToTheSideSitsAfterThePageJustAboveTheWideBreakpoint() = aSideCanvasSitsAfterThePage(760.dp)

    @Test
    fun aCanvasSharesTheMainLaneWithThePage() = window(1200.dp, lanes(main = listOf(PageSlot, NotesSlot))) {
        awaitBoard(NotesId)
        assertBoardTitled(NotesId, NotesTitle)
        val page = bounds(Page)
        val board = bounds(boardTag(NotesId))
        assertDp(page.width, board.width, "the page and the board share the lane equally")
        assertEquals(true, board.left >= page.right, "the board sits after the page")
        assertDp(1200.dp - HostPaneWidth, board.right, "the lane still ends at the host pane")
    }

    /** A side board takes a side panel's width, so the main lane needs a wide window to hold a second module. */
    @Test
    fun twoCanvasModulesShowTwoBoards() = window(1800.dp, lanes(main = listOf(PageSlot, NotesSlot), side = listOf(PlanSlot))) {
        awaitBoard(NotesId)
        awaitBoard(PlanId)
        assertBoardTitled(NotesId, NotesTitle)
        assertBoardTitled(PlanId, PlanTitle)
        val notes = hasAnyAncestor(hasTestTag(boardTag(NotesId)))
        assertEquals(0, onAllNodes(hasText(PlanTitle, substring = true) and notes).fetchSemanticsNodes().size, "each board is its own")
        assertEquals(true, bounds(boardTag(NotesId)).right <= bounds(boardTag(PlanId)).left)
        assertEquals(listOf(NotesId, PlanId), opened.sorted(), "each board is opened once")
    }

    private fun aCompactWindowKeepsThePage(width: Dp) =
        window(width, lanes(main = listOf(NotesSlot, PageSlot), side = listOf(PlanSlot, HostPaneSlot))) {
            waitForIdle()
            assertDp(width - HostPaneWidth, bounds(Page).right, "the page runs to the host pane at $width")
            assertNoCanvasModule()
            assertEquals(emptyList(), opened, "a board left out of a compact window is never opened at $width")
        }

    @Test
    fun aPhoneKeepsThePageAndLeavesCanvasModulesOut() = aCompactWindowKeepsThePage(360.dp)

    @Test
    fun aWindowJustBelowTheWideBreakpointKeepsThePageAndLeavesCanvasModulesOut() = aCompactWindowKeepsThePage(700.dp)

    @Test
    fun aBoardNoStoreHoldsIsUnavailable() = window(1200.dp, lanes(side = listOf(ModuleSlot(ShellViews.CANVAS, "canvas-missing")))) {
        waitUntil(timeoutMillis = TimeoutMillis) { onAllNodesWithTag(ShellModuleTags.UNAVAILABLE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ShellModuleTags.UNAVAILABLE).assertIsDisplayed()
        onNodeWithTag(Page).assertIsDisplayed()
    }

    @Test
    fun aCanvasSlotWithoutABoardIsUnavailable() = window(1200.dp, lanes(side = listOf(ModuleSlot(ShellViews.CANVAS)))) {
        onNodeWithTag(ShellModuleTags.UNAVAILABLE).assertIsDisplayed()
        assertEquals(emptyList(), opened)
    }

    @Test
    fun aHostThatCannotOpenBoardsShowsTheModuleAsUnavailable() = window(1200.dp, lanes(side = listOf(NotesSlot)), boards = null) {
        onNodeWithTag(ShellModuleTags.UNAVAILABLE).assertIsDisplayed()
        onNodeWithTag(boardTag(NotesId)).assertDoesNotExist()
    }

    @Test
    fun aCanvasModuleKeepsItsBoardWhenTheLaneIsReordered() = runDesktopComposeUiTest(width = 1800, height = WindowHeight.value.toInt()) {
        var order by mutableStateOf(listOf(PageSlot, NotesSlot, PlanSlot))
        setContent {
            CompositionLocalProvider(LocalCanvasBoardSource provides source) {
                LettaShellWorkspace(state(lanes(main = order)), host(), Modifier.size(width = 1800.dp, height = WindowHeight))
            }
        }
        awaitBoard(NotesId)
        awaitBoard(PlanId)
        assertEquals(true, bounds(boardTag(NotesId)).left < bounds(boardTag(PlanId)).left)

        order = listOf(PageSlot, PlanSlot, NotesSlot)
        waitForIdle()

        assertEquals(true, bounds(boardTag(PlanId)).left < bounds(boardTag(NotesId)).left)
        assertBoardTitled(NotesId, NotesTitle)
        assertBoardTitled(PlanId, PlanTitle)
        assertEquals(listOf(NotesId, PlanId), opened.sorted(), "neither board was opened again by the move")
    }

    /**
     * The board open in the page and the same board in a module are one session: the module never
     * opens a second copy, and a change to the board shows in both.
     */
    @Test
    fun theBoardOpenInThePageIsTheModuleSession() = runDesktopComposeUiTest(width = 1440, height = WindowHeight.value.toInt()) {
        val registry = CanvasSessionRegistry()
        val pageSession = runBlocking { CanvasSession.open(store, CanvasId(NotesId)) }!!
        val pageWithBoard = host { modifier -> CanvasWorkspace(modifier.fillMaxSize().testTag(Page), session = pageSession) }
        var config by mutableStateOf(lanes())
        setContent {
            CompositionLocalProvider(LocalCanvasSessionRegistry provides registry, LocalCanvasBoardSource provides source) {
                LettaShellWorkspace(state(config), pageWithBoard, Modifier.size(width = 1440.dp, height = WindowHeight))
            }
        }
        waitUntil(timeoutMillis = TimeoutMillis) { registry.get(CanvasId(NotesId)) === pageSession }

        config = lanes(side = listOf(NotesSlot))
        awaitBoard(NotesId)
        assertBoardTitled(NotesId, NotesTitle)
        assertEquals(emptyList(), opened, "the module shows the page's live board instead of opening another")

        runBlocking { pageSession.updateTitle("Renamed notes") }
        waitUntil(timeoutMillis = TimeoutMillis) {
            onAllNodes(hasText("Renamed notes", substring = true)).fetchSemanticsNodes().size >= 2
        }
        assertBoardTitled(NotesId, "Renamed notes")
        assertEquals("Renamed notes", runBlocking { store.get(CanvasId(NotesId)) }?.title, "the store holds the one board's edit")
    }

    /**
     * Through the window's table, a board the page holds is the module's session too; the module
     * gives its hold back when it leaves, and the page keeps the session it still shows.
     */
    @Test
    fun theModuleSharesThePageHoldAndGivesItsOwnBack() = runDesktopComposeUiTest(width = 1440, height = WindowHeight.value.toInt()) {
        val boards = CanvasBoardSessions(store)
        val pageSession = runBlocking { boards.acquire(CanvasId(NotesId)) }!!
        val shown = mutableListOf<CanvasSession?>()
        val shared = canvasBoardSource(boards)
        val recording = object : CanvasBoardSource by shared {
            override suspend fun open(canvasId: CanvasId): CanvasSession? = shared.open(canvasId).also { shown += it }
        }
        var config by mutableStateOf(lanes(side = listOf(NotesSlot)))
        setContent {
            CompositionLocalProvider(LocalCanvasBoardSource provides recording) {
                LettaShellWorkspace(state(config), host(), Modifier.size(width = 1440.dp, height = WindowHeight))
            }
        }
        awaitBoard(NotesId)
        assertBoardTitled(NotesId, NotesTitle)
        assertSame(pageSession, shown.single(), "the module shows the session the page holds")
        assertEquals(2, boards.holds(CanvasId(NotesId)))

        config = lanes()
        waitUntil(timeoutMillis = TimeoutMillis) { boards.holds(CanvasId(NotesId)) == 1 }
        assertSame(pageSession, runBlocking { boards.acquire(CanvasId(NotesId)) }, "the page still has its session")
    }

    /** A board only the module held is forgotten once the module leaves, so it is read afresh next time. */
    @Test
    fun aModuleLeavingGivesBackTheOnlyHold() = runDesktopComposeUiTest(width = 1200, height = WindowHeight.value.toInt()) {
        val boards = CanvasBoardSessions(store)
        var config by mutableStateOf(lanes(side = listOf(PlanSlot)))
        setContent {
            ProvideCanvasBoardSource(boards) {
                LettaShellWorkspace(state(config), host(), Modifier.size(width = 1200.dp, height = WindowHeight))
            }
        }
        awaitBoard(PlanId)
        assertBoardTitled(PlanId, PlanTitle)
        assertEquals(1, boards.holds(CanvasId(PlanId)))

        config = lanes()
        waitUntil(timeoutMillis = TimeoutMillis) { boards.holds(CanvasId(PlanId)) == 0 }
        onNodeWithTag(boardTag(PlanId)).assertDoesNotExist()
    }
}
