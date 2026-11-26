package com.letta.mobile.feature.chat.screen.shared

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.openedChatViewModel
import com.letta.mobile.feature.chat.screen.rememberChatScreenSubagentBarState
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailTags
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutBinding
import com.letta.mobile.ui.shell.shortcuts.ShortcutPlatform
import com.letta.mobile.ui.shell.shortcuts.WorkspaceShortcuts
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderTags
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigation
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's Back out of a chat opened from the agent rail. Home, the drawer's menu, an agent's
 * orb, and the chat opens on its full page (the default first view). One Back must land on Home:
 * not after phantom presses, and never by way of the conversation's canvas, which nobody opened.
 *
 * The workspace is the shared one the app mounts (its shortcut root, its modal drawer); the page is
 * the app's [SharedChatPage] with a canvas slot, as the app provides one; the host's Back is the
 * app shell's rule (close the drawer, else go Home). Back is a real KEYCODE_BACK through the
 * window, so Compose's own handling of the key runs before the activity's back dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@Tag("integration")
class RailChatBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewModel: AdminChatViewModel = openedChatViewModel(
        pagingHost(),
        TestData.agent(AGENT, "Bench"),
        "conversation-rail",
        "rail-back",
    )

    /** What the person sees: Home, the chat's full page, or the conversation's canvas. */
    private enum class Seen { Home, Chat, Canvas }

    private var page by mutableStateOf(Page.Home)
    private var openOnCanvas = false
    private var withCanvas = true
    private var withShortcuts = true
    private val drawer = ShellWorkspaceDrawer()

    /** Presses the app shell's Back handler took (the drawer's close, or the way Home). */
    private var hostBacks = 0

    private enum class Page { Home, Chat }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun oneBackFromARailOpenedChatLandsOnHomeWithoutPassingTheCanvas() {
        openChatFromTheRail()
        assertEquals(Seen.Chat, seen())
        assertFalse("the orb's pick closes the drawer", drawer.open)

        val trail = mutableListOf<Seen>()
        while (trail.size < MAX_PRESSES && trail.lastOrNull() != Seen.Home) {
            pressBack()
            trail += seen()
        }

        assertFalse("Back showed a canvas nobody opened: $trail", Seen.Canvas in trail)
        assertEquals("one Back from the chat lands on Home: $trail", listOf(Seen.Home), trail)
        assertFalse(drawer.open)
    }

    /**
     * The shared half alone. Without a canvas the page has no Back of its own, so only Compose's
     * focus walk stands between the press and the host: focus left in the closed drawer's sheet
     * must not swallow it.
     */
    @Test
    fun focusLeftInTheClosedDrawerDoesNotSwallowBack() {
        withCanvas = false
        openChatFromTheRail()
        assertFalse(drawer.open)

        pressBack()
        assertEquals("the first Back reached the host", 1, hostBacks)
        assertEquals(Seen.Home, seen())
    }

    /**
     * The prompt holding focus with no soft keyboard up (a hardware keyboard, or the person hid the
     * keyboard): Back is not spent taking focus out of the field first. With the keyboard up, the
     * keyboard takes one Back to hide itself, as everywhere on Android; that press never reaches the app.
     * No canvas, so this pins the shared half alone.
     */
    @Test
    fun aFocusedPromptDoesNotCostAnExtraBack() {
        withCanvas = false
        openChatFromTheRail()
        compose.onNodeWithTag(PROMPT_TAG).requestFocus()
        compose.waitForIdle()

        pressBack()
        assertEquals("the first Back reached the host", 1, hostBacks)
        assertEquals(Seen.Home, seen())
    }

    /**
     * The Android half alone. A host without the shared keymap has no focusable root, so no focus
     * walk can take the press: what Back does is the page's rule. A chat that opened on its full
     * page leaves on Back; its canvas is never shown.
     */
    @Test
    fun aChatOpenedOnItsFullPageLeavesOnBackRatherThanShowingItsCanvas() {
        withShortcuts = false
        openChatFromTheRail()

        pressBack()
        assertEquals("Back never shows a canvas nobody opened", Seen.Home, seen())
    }

    @Test
    fun backStillReturnsToTheCanvasThePersonCameFrom() {
        // "Open conversations on the canvas": the person starts on the board, then opens the full page.
        openOnCanvas = true
        openChatFromTheRail()
        assertEquals(Seen.Canvas, seen())
        // The chat head opens its card; the card's chevron opens the full chat.
        compose.onNodeWithTag(CHAT_HEAD_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OPEN_CHAT_TAG).performClick()
        assertEquals(Seen.Chat, seen())

        pressBack()
        assertEquals("Back returns to the canvas the person came from", Seen.Canvas, seen())
        // The bubble's card is as it was left (open), and Back folds it first; then Home.
        pressBack()
        assertEquals(Seen.Canvas, seen())
        pressBack()
        assertEquals(Seen.Home, seen())
    }

    private fun openChatFromTheRail() {
        compose.setContent { Workspace() }
        compose.waitForIdle()
        assertEquals(Seen.Home, seen())
        compose.onNodeWithTag(ShellWorkspaceHeaderTags.MENU_BUTTON).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ShellAgentRailTags.orb(AGENT_NAME)).performClick()
        compose.waitForIdle()
        hostBacks = 0
    }

    @Composable
    private fun Workspace() {
        // The app shell's Back (AndroidShellWorkspace): close the drawer, else go Home.
        BackHandler(enabled = drawer.open || page != Page.Home) {
            hostBacks++
            if (drawer.open) drawer.open = false else page = Page.Home
        }
        LettaShellWorkspace(
            state = LettaShellWorkspaceState(
                header = ShellWorkspaceHeaderState(title = "Home"),
                navigation = ShellWorkspaceNavigation(
                    rail = ShellLibraryRailState(agents = listOf(AGENT to AGENT_NAME), focus = ShellRailFocus()),
                    panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Home"), home = true),
                ),
                layout = ShellWorkspaceLayout(lanes = ShellLayoutConfig.Default),
            ),
            host = LettaShellWorkspaceHost(
                actions = ShellWorkspaceNavigationActions(
                    rail = ShellAgentRailActions(onAgentSelected = { page = Page.Chat }),
                ),
                slots = LettaShellWorkspaceSlots(
                    main = { modifier -> Main(modifier) },
                    agentCard = {},
                    sidePane = {},
                    overlays = {},
                ),
                chrome = ShellWindowChrome.Inline,
                drawer = drawer,
                // Android binds the shared keymap, so the workspace root takes focus as on the phone.
                events = ShellWorkspaceEvents(
                    shortcuts = ShellShortcutBinding(ShortcutPlatform.Android, WorkspaceShortcuts) {}.takeIf { withShortcuts },
                ),
            ),
        )
    }

    @Composable
    private fun Main(modifier: Modifier) {
        when (page) {
            Page.Home -> Text("Home", modifier.testTag(HOME_TAG))
            Page.Chat -> Box(modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalChatCanvasSlot provides canvasSlot.takeIf { withCanvas }) { ChatPage() }
            }
        }
    }

    /** The app provides the page a canvas; the board is drawn only while the page is on it. */
    private val canvasSlot = ChatCanvasSlot { _, _, _ -> Box(Modifier.fillMaxSize().testTag(CANVAS_TAG)) }

    @Composable
    private fun ChatPage() {
        val navigation = remember { ChatScreenNavigationCallbacks() }
        SharedChatPage(
            SharedChatPageParams(
                session = rememberAdminChatPageSession(viewModel, navigation),
                navigation = navigation,
                chatMode = "interactive",
                fontScale = 1f,
                hapticsEnabled = false,
                pagedTimeline = null,
                // Off by default: conversations open on the full-screen chat.
                openOnCanvas = openOnCanvas,
                subagents = SharedChatSubagentInputs(
                    source = viewModel.activeSubagentSource,
                    selfTodoSource = viewModel.selfTodoSource,
                    barState = rememberChatScreenSubagentBarState(
                        viewModel.activeSubagentSource, viewModel.selfTodoSource, viewModel.conversationId?.value,
                    ),
                ),
            ),
        )
    }

    /** The full page draws the prompt field; the canvas draws its own bar's field instead. */
    private fun seen(): Seen {
        compose.waitForIdle()
        return when {
            page == Page.Home -> Seen.Home
            compose.onAllNodesWithTag(PROMPT_TAG).fetchSemanticsNodes().isNotEmpty() -> Seen.Chat
            else -> Seen.Canvas
        }
    }

    /** A real KEYCODE_BACK through the window, as the device's Back key arrives. */
    private fun pressBack() {
        compose.runOnUiThread {
            val window = compose.activity.window.decorView
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    private fun pagingHost(): ChatPagingHost {
        val presentation = ChatPagingPresentation(settled = flowOf(PagingData.empty()), live = MutableStateFlow(emptyList()), close = {})
        return ChatPagingHost().apply { openCanonical = { _, _, _, _ -> presentation } }
    }

    private companion object {
        const val AGENT = "agent-bench"
        const val AGENT_NAME = "Bench"
        const val HOME_TAG = "rail-back-home"
        const val CANVAS_TAG = "rail-back-canvas"

        /** sharedUI's prompt-field, open-chat and chat-head tags, internal to that module. */
        const val PROMPT_TAG = "composer-input"
        const val OPEN_CHAT_TAG = "composer-touch-restore"
        const val CHAT_HEAD_TAG = "chat-touch-head"

        /** More than the device needed (four), so a phantom press shows in the trail rather than stopping it. */
        const val MAX_PRESSES = 5
    }
}
