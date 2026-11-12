@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.controller.extras.identity.CallerIdentity.VERIFIED
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.shell.layout.edit.ShellLayoutTool
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.ui.host.PASTE_BLOCKED_HINT
import com.letta.mobile.ui.settings.SettingsHeader
import com.letta.mobile.ui.settings.SettingsSection
import com.letta.mobile.ui.settings.SettingsSectionTags
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.TOUCH_HEAD_TAG
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlinx.coroutines.flow.MutableStateFlow
import com.letta.mobile.ui.canvas.boards.CanvasBoardSource
import com.letta.mobile.ui.canvas.boards.LocalCanvasBoardSource
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.host.OverlayHandle
import com.letta.mobile.ui.host.OverlayHostRoot
import com.letta.mobile.ui.host.OverlayPresentation
import com.letta.mobile.ui.host.OverlayPriority
import com.letta.mobile.ui.host.rememberOverlayHost
import kotlin.test.assertSame
import com.letta.mobile.ui.settings.LocalShellLayoutReset
import com.letta.mobile.ui.shell.pages.home.LocalHomeLane
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val Page = "layout-tool-page"
private const val HostPane = "layout-tool-host-pane"
private const val ResetProbe = "layout-tool-reset"
private const val BoardId = "canvas-layout-tool-board"
private const val TimeoutMillis = 10_000L
private val HostPaneWidth = 100.dp
private val WindowHeight = 800.dp
private val Widths = listOf(360.dp, 700.dp, 760.dp, 1200.dp)

/**
 * An agent's `shell_layout` call changes the shell on screen, the way every host mounts it (a
 * [rememberShellLayoutController] whose lanes feed the workspace): Home's sections reorder, a side
 * menu part goes, a canvas board snaps into the side lane. The person gets a notice naming the
 * agent, Undo puts the layout back, and Reset layout restores the default.
 */
class ShellLayoutAgentToolUiTest {
    private val port = ShellLayoutAgentPort()
    private val tool = ShellLayoutTool(port)
    private val boards = InMemoryCanvasDocumentStore()
    private val source = CanvasBoardSource { id -> CanvasSession.open(boards, id) }

    /** The home lane the page last read from the workspace. */
    private var pageHomeLane: List<RuntimeViewId>? = null

    init {
        runBlocking { CanvasSession.create(boards, CanvasCreateOptions(title = "Layout board", canvasId = CanvasId(BoardId))) }
    }

    private fun state(lanes: ShellLayoutConfig) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Window title"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
        ),
        layout = ShellWorkspaceLayout(lanes = lanes),
    )

    private fun host(
        onOverlay: (OverlayHost?) -> Unit = {},
        drawerOpen: Boolean = true,
        androidStyle: Boolean = false,
        pageContent: @Composable () -> Unit = {},
    ) = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        slots = LettaShellWorkspaceSlots(
            main = { modifier ->
                pageHomeLane = LocalHomeLane.current
                onOverlay(LocalOverlayHost.current)
                Box(modifier.testTag(Page)) {
                    pageContent()
                    LocalShellLayoutReset.current?.let { reset ->
                        Text("Reset layout", Modifier.testTag(ResetProbe).clickable(onClick = reset))
                    }
                }
            },
            agentCard = {},
            sidePane = { if (!androidStyle) Box(Modifier.width(HostPaneWidth).fillMaxHeight().testTag(HostPane)) },
            overlays = {},
        ),
        chrome = if (androidStyle) ShellWindowChrome.Inline else ShellWindowChrome.Hoisted {},
        drawer = ShellWorkspaceDrawer().apply { open = drawerOpen },
        events = ShellWorkspaceEvents(),
    )

    /** The workspace [width] wide, mounted as a host mounts it over fresh settings. */
    private fun shell(width: Dp, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width.value.toInt(), height = WindowHeight.value.toInt()) {
            val settings = InMemorySecureSettingsStore()
            val host = host()
            setContent {
                CompositionLocalProvider(LocalShellLayoutAgentPort provides port, LocalCanvasBoardSource provides source) {
                    val controller = rememberShellLayoutController(null, settings)
                    LettaShellWorkspace(
                        state(controller.workspaceLayout().lanes),
                        host,
                        Modifier.size(width = width, height = WindowHeight),
                    )
                }
            }
            waitForIdle()
            block()
        }

    private fun ComposeUiTest.agentCalls(input: String): ExternalToolResult {
        val caller = ExternalToolCaller(VERIFIED, agentId = "agent-a")
        val result = runBlocking { tool.invoke(Json.parseToJsonElement(input).jsonObject, caller) }
        waitForIdle()
        return result
    }

    private fun ComposeUiTest.applies(ops: String) =
        assertIs<ExternalToolResult.Success>(agentCalls("""{"action":"apply","ops":[$ops]}"""), "the agent's edit applies")

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = TimeoutMillis) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.undo() {
        awaitText(Overlay.UNDO_LABEL)
        onNodeWithText(Overlay.UNDO_LABEL).performClick()
        waitForIdle()
    }

    private fun assertDp(expected: Dp, actual: Dp, what: String) =
        assertEquals(expected.value, actual.value, absoluteTolerance = 1f, message = what)

    @Test
    fun settingsPasteHintUsesTheWorkspaceHostWithOrWithoutAWindowRoot() = listOf(false, true).forEach { outer ->
        runDesktopComposeUiTest(width = 360, height = 800) {
            val settings = InMemorySecureSettingsStore()
            val clipboard = object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = error("clipboard read refused")
                override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
            }
            val shellHost = host(drawerOpen = false, androidStyle = true) {
                SettingsSection(SettingsHeader("backend", "Backend")) {
                    TextFieldRow(SettingsRow.Text("server-url", "Host address"), "draft", paste = true) {}
                }
            }
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard, LocalShellLayoutAgentPort provides port) {
                    val controller = rememberShellLayoutController(null, settings)
                    val workspace: @Composable () -> Unit = {
                        LettaShellWorkspace(state(controller.workspaceLayout().lanes), shellHost, Modifier.size(360.dp, WindowHeight))
                    }
                    if (outer) OverlayHostRoot(rememberOverlayHost()) { workspace() } else workspace()
                }
            }
            waitForIdle()
            onNodeWithTag(SettingsSectionTags.PASTE_PREFIX + "server-url").performClick()
            awaitText(PASTE_BLOCKED_HINT)
        }
    }

    @Test
    fun noticeCanBeTouchedAboveFullScreenChatWithCanvas() = runDesktopComposeUiTest(width = 360, height = 800) {
        val settings = InMemorySecureSettingsStore()
        val presentation = mutableStateOf(ChatSurfacePresentation.ChatFirst)
        val chat = object : ChatSessionPort {
            override val uiState = MutableStateFlow(ChatUiState(conversationState = ConversationState.Ready("conv-1"), agentName = "Ada"))
            override val composer = MutableStateFlow(ChatComposerUiState())
            override val actions = RecordingChatActions()
        }
        val host = host(drawerOpen = false, androidStyle = true) {
            ProvideLettaHost(LettaHost.Touch) {
                ChatSurface(
                    port = chat,
                    presentation = presentation.value,
                    onIntent = {},
                    host = ChatSurfaceHost(),
                    canvas = { Box(Modifier.size(360.dp, WindowHeight)) },
                )
            }
        }
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                val controller = rememberShellLayoutController(null, settings)
                LettaShellWorkspace(state(controller.workspaceLayout().lanes), host, Modifier.size(360.dp, WindowHeight))
            }
        }
        fun checkNotice() {
            applies("""{"op":"move","lane":"home","view":"home.recents","index":0}""")
            awaitText("Layout changed by Ada")
            waitForIdle()
            val undoBounds = onNodeWithText(Overlay.UNDO_LABEL).getUnclippedBoundsInRoot()
            val pageBounds = onNodeWithTag(Page).getUnclippedBoundsInRoot()
            assertTrue(undoBounds.left >= pageBounds.left && undoBounds.right <= pageBounds.right, "Undo is off screen: $undoBounds")
            onNodeWithText(Overlay.UNDO_LABEL).assertIsDisplayed().performTouchInput { click() }
            waitForIdle()
            assertEquals(HomeViews.Default, pageHomeLane)
        }
        waitForIdle()
        checkNotice()
        runOnIdle { presentation.value = ChatSurfacePresentation.CanvasFirst }
        waitForIdle()
        checkNotice()
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        waitForIdle()
        checkNotice()
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        runOnIdle { presentation.value = ChatSurfacePresentation.ChatFirst }
        waitForIdle()
        checkNotice()
    }

    @Test
    fun noticeIsDrawnOverAnOpenCompactDrawerAndItsUndoCanBePressed() = runDesktopComposeUiTest(width = 360, height = 800) {
        val settings = InMemorySecureSettingsStore()
        val chat = object : ChatSessionPort {
            override val uiState = MutableStateFlow(ChatUiState(conversationState = ConversationState.Ready("conv-1"), agentName = "Ada"))
            override val composer = MutableStateFlow(ChatComposerUiState())
            override val actions = RecordingChatActions()
        }
        // The compact shell with its drawer open over a chat page whose composer holds a snackbar slot.
        val host = host(drawerOpen = true, androidStyle = true) {
            ProvideLettaHost(LettaHost.Touch) {
                ChatSurface(port = chat, presentation = ChatSurfacePresentation.ChatFirst, onIntent = {}, host = ChatSurfaceHost())
            }
        }
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                val controller = rememberShellLayoutController(null, settings)
                LettaShellWorkspace(state(controller.workspaceLayout().lanes), host, Modifier.size(360.dp, WindowHeight))
            }
        }
        waitForIdle()
        applies("""{"op":"move","lane":"home","view":"home.recents","index":0}""")
        awaitText("Layout changed by Ada")
        waitForIdle()
        // A real press at the button's place: the drawer's scrim takes it unless the notice is above it.
        onNodeWithText(Overlay.UNDO_LABEL).assertIsDisplayed().performTouchInput { click() }
        waitForIdle()
        assertEquals(HomeViews.Default, pageHomeLane, "Undo under the open drawer could not be pressed")
    }

    @Test
    fun changeWhileWorkspaceIsAbsentIsOfferedWhenItReturns() = runDesktopComposeUiTest(width = 360, height = 800) {
        val settings = InMemorySecureSettingsStore()
        val visible = mutableStateOf(false)
        val host = host()
        setContent {
            CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                val controller = rememberShellLayoutController(null, settings)
                if (visible.value) {
                    LettaShellWorkspace(state(controller.workspaceLayout().lanes), host, Modifier.size(360.dp, WindowHeight))
                }
            }
        }
        waitForIdle()
        applies("""{"op":"move","lane":"home","view":"home.recents","index":0}""")
        runOnIdle { visible.value = true }
        awaitText("Layout changed by Ada")
        undo()
        assertEquals(HomeViews.Default, pageHomeLane)
    }

    @Test
    fun aQueuedNoticeRemainsUnseenUntilItReachesTheScreen() = runDesktopComposeUiTest(width = 360, height = 800) {
        val settings = InMemorySecureSettingsStore()
        val visible = mutableStateOf(true)
        val overlayHost = OverlayHost()
        val host = host()
        setContent {
            OverlayHostRoot(overlayHost) {
                CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                    val controller = rememberShellLayoutController(null, settings)
                    if (visible.value) {
                        LettaShellWorkspace(state(controller.workspaceLayout().lanes), host, Modifier.size(360.dp, WindowHeight))
                    }
                }
            }
        }
        waitForIdle()
        lateinit var blocker: OverlayHandle
        runOnIdle {
            blocker = overlayHost.show(Overlay.Snackbar("Important", presentation = OverlayPresentation(OverlayPriority.Critical, null)))
        }
        applies("""{"op":"move","lane":"home","view":"home.recents","index":0}""")
        assertEquals("Important", overlayHost.snackbar?.overlay?.message)
        assertTrue(port.peekUnseenChange() != null)
        runOnIdle { visible.value = false }
        waitForIdle()
        assertTrue(overlayHost.waiting.isEmpty(), "the hidden workspace withdraws its queued notice")
        runOnIdle { blocker.dismiss(); visible.value = true }
        awaitText("Layout changed by Ada")
        undo()
        assertEquals(HomeViews.Default, pageHomeLane)
    }

    @Test
    fun workspaceAndPageShareTheWindowOverlayQueue() = runDesktopComposeUiTest(width = 360, height = 800) {
        val settings = InMemorySecureSettingsStore()
        var window: OverlayHost? = null
        var page: OverlayHost? = null
        val host = host(onOverlay = { page = it })
        setContent {
            val outer = rememberOverlayHost()
            window = outer
            OverlayHostRoot(outer) {
                CompositionLocalProvider(LocalShellLayoutAgentPort provides port) {
                    val controller = rememberShellLayoutController(null, settings)
                    LettaShellWorkspace(state(controller.workspaceLayout().lanes), host, Modifier.size(360.dp, WindowHeight))
                }
            }
        }
        waitForIdle()
        assertSame(window, page, "the page and activity must use one window overlay queue")
        applies("""{"op":"move","lane":"home","view":"home.recents","index":0}""")
        awaitText("Layout changed by Ada")
        undo()
        assertEquals(HomeViews.Default, pageHomeLane)
    }

    @Test
    fun anAgentReordersHomeAndTheNoticeOffersUndo() = Widths.forEach { width ->
        shell(width) {
            assertEquals(HomeViews.Default, pageHomeLane, "Home starts in its default order at $width")
            applies("""{"op":"move","lane":"home","view":"home.recents","index":0}""")
            assertEquals(HomeViews.RECENTS, pageHomeLane?.first(), "Home shows Recents first at $width")
            awaitText("Layout changed by Ada")

            undo()
            assertEquals(HomeViews.Default, pageHomeLane, "Undo puts Home back at $width")
        }
    }

    @Test
    fun anAgentHidesASideMenuPart() = listOf(360.dp, 700.dp, 1200.dp).forEach { width ->
        shell(width) {
            onNodeWithText("New chat").assertExists()
            applies("""{"op":"remove","lane":"panel","view":"panel.newChat"}""")
            onNodeWithText("New chat").assertDoesNotExist()
            onNodeWithText("Settings").assertExists()

            undo()
            onNodeWithText("New chat").assertExists()
        }
    }

    @Test
    fun anAgentHidesARailPartJustAboveTheWideBreakpoint() = shell(760.dp) {
        onNodeWithContentDescription("New", useUnmergedTree = true).assertExists()
        applies("""{"op":"remove","lane":"rail","view":"rail.new"}""")
        onNodeWithContentDescription("New", useUnmergedTree = true).assertDoesNotExist()
        undo()
        onNodeWithContentDescription("New", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anAgentSnapsACanvasIntoTheSideLane() = Widths.forEach { width ->
        shell(width) {
            val board = shellCanvasModuleTag(CanvasId(BoardId))
            applies("""{"op":"add","lane":"side","view":"shell.canvas","object":"$BoardId","index":0}""")
            if (width >= LettaDimens.Pane.wideBreakpoint) {
                waitUntil(timeoutMillis = TimeoutMillis) { onAllNodesWithTag(board).fetchSemanticsNodes().isNotEmpty() }
                val bounds = onNodeWithTag(board).getUnclippedBoundsInRoot()
                assertTrue(onNodeWithTag(Page).getUnclippedBoundsInRoot().right <= bounds.left, "the board sits after the page at $width")
                assertDp(width - HostPaneWidth, bounds.right, "the board runs up to the host pane at $width")
            } else {
                onNodeWithTag(board).assertDoesNotExist()
                val page = onNodeWithTag(Page).getUnclippedBoundsInRoot()
                assertDp(width - HostPaneWidth, page.right, "a compact window keeps the page at $width")
            }
            awaitText("Layout changed by Ada")

            undo()
            onNodeWithTag(board).assertDoesNotExist()
        }
    }

    @Test
    fun resetLayoutRestoresTheDefaultAndIsItselfUndoable() = Widths.forEach { width ->
        shell(width) {
            onNodeWithTag(ResetProbe).assertDoesNotExist()
            applies("""{"op":"move","lane":"home","view":"home.recents","index":0}""")
            awaitText("Layout changed by Ada")

            // The compact window's open drawer covers the page, so run the row's click directly.
            onNodeWithTag(ResetProbe).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(HomeViews.Default, pageHomeLane, "Reset layout restores the default at $width")
            onNodeWithTag(ResetProbe).assertDoesNotExist()
            awaitText("Layout reset to default")

            undo()
            assertEquals(HomeViews.RECENTS, pageHomeLane?.first(), "undoing the reset brings the change back at $width")
        }
    }

    @Test
    fun readingTheLayoutOrADryRunShowsNoNotice() = shell(1200.dp) {
        assertIs<ExternalToolResult.Success>(agentCalls("""{"action":"get"}"""))
        assertIs<ExternalToolResult.Success>(
            agentCalls("""{"action":"apply","dry_run":true,"ops":[{"op":"move","lane":"home","view":"home.recents","index":0}]}"""),
        )
        onNodeWithText(Overlay.UNDO_LABEL).assertDoesNotExist()
        assertEquals(HomeViews.Default, pageHomeLane)
    }

    @Test
    fun aRefusedEditChangesNothingOnScreen() = shell(1200.dp) {
        val refused = agentCalls("""{"action":"apply","ops":[{"op":"remove","lane":"main","view":"shell.page"}]}""")
        assertTrue("page_stays_in_main" in assertIs<ExternalToolResult.Error>(refused).error)
        onNodeWithTag(Page).assertExists()
        onNodeWithText(Overlay.UNDO_LABEL).assertDoesNotExist()
    }

    @Test
    fun anAgentCannotTakeHomeOffTheRail() = shell(760.dp) {
        val refused = agentCalls("""{"action":"apply","ops":[{"op":"remove","lane":"rail","view":"rail.home"}]}""")
        assertTrue("home_stays" in assertIs<ExternalToolResult.Error>(refused).error)
        assertTrue(onAllNodesWithContentDescription("Home", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText(Overlay.UNDO_LABEL).assertDoesNotExist()
    }

    @Test
    fun theShellDetachesWhenItLeaves() {
        shell(1200.dp) { assertTrue(port.attached.value != null) }
        assertEquals(null, port.attached.value)
        assertTrue("No app shell" in assertIs<ExternalToolResult.Error>(runBlocking {
            tool.invoke(Json.parseToJsonElement("""{"action":"get"}""").jsonObject, ExternalToolCaller(VERIFIED, agentId = "agent-a"))
        }).error)
    }
}
