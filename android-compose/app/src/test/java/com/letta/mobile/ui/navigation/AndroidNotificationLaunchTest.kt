package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.FocusAvailability
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.feature.chat.screen.SharedNavDrawerViewModel
import com.letta.mobile.ui.screens.config.SettingsDestinations
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A notification tap reaches the workspace as MainActivity's launch target. Through the real startup
 * binding and launch effect, it must focus exactly its agent and conversation, be handed back once,
 * and let a later tap replace it; a target that cannot be a chat is dropped without navigating.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class AndroidNotificationLaunchTest {
    @get:Rule val compose = createComposeRule()
    private val config = LettaConfig("config", LettaConfig.Mode.SELF_HOSTED, "https://a.invalid", accessToken = "principal-a")
    private val graph = androidFocusGraph(config)
    private val source = AndroidShellChatPort.sourceKey(graph)
    private val drawer = androidFocusDrawer(graph).also {
        every { it.sources.sessions.currentGraph } returns MutableStateFlow(graph)
    }

    /** MainActivity's mailbox: the launch target, cleared by the workspace's consumption callback. */
    private var target: AppLaunchTarget? by mutableStateOf(null)
    private var consumed by mutableIntStateOf(0)
    private lateinit var workspace: ShellWorkspaceState

    @After fun tearDown() = drawer.viewModelScope.cancel()

    @Test
    fun coldStartTapOpensExactlyItsConversationAndIsConsumedOnce() {
        target = NotificationNavigationTarget("agent-n", "conversation-n")
        compose.setContent { Workspace() }
        compose.runOnIdle {
            assertEquals(exact("agent-n", "conversation-n"), workspace.currentFocus)
            assertEquals(ShellDestination.Chat("agent-n", "conversation-n"), workspace.destination)
            assertEquals(1, consumed)
            assertNull(target)
        }
    }

    @Test
    fun consumedTapDoesNotReopenAfterTheUserLeavesOrTheActivityIsRecreated() {
        val restoration = StateRestorationTester(compose)
        target = NotificationNavigationTarget("agent-n", "conversation-n")
        restoration.setContent { Workspace() }
        compose.runOnIdle {
            assertEquals(exact("agent-n", "conversation-n"), workspace.currentFocus)
            workspace.navigate(ShellDestination.Home)
        }
        compose.runOnIdle { assertEquals(CurrentFocus(), workspace.currentFocus) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(CurrentFocus(), workspace.currentFocus)
            assertEquals(1, consumed)
        }
    }

    @Test
    fun aSecondTapWhileTheAppIsOpenReplacesTheFirst() {
        target = NotificationNavigationTarget("agent-n", "conversation-n")
        compose.setContent { Workspace() }
        compose.runOnIdle { assertEquals(exact("agent-n", "conversation-n"), workspace.currentFocus) }
        target = NotificationNavigationTarget("agent-m", "conversation-m")
        compose.runOnIdle {
            assertEquals(exact("agent-m", "conversation-m"), workspace.currentFocus)
            assertEquals(2, consumed)
            assertNull(target)
        }
    }

    @Test
    fun aConversationTheClientHasNotLoadedIsStillTheExactTargetAndNotMarkedMissing() {
        // Nothing is in the roster or fleet yet (cold start): the chat page loads this one conversation.
        target = NotificationNavigationTarget("agent-unlisted", "conversation-unlisted")
        compose.setContent { Workspace() }
        compose.runOnIdle {
            assertEquals(exact("agent-unlisted", "conversation-unlisted"), workspace.currentFocus)
            assertEquals(FocusAvailability.Unknown, workspace.conversationAvailability)
            assertEquals(1, consumed)
        }
    }

    @Test
    fun aTargetThatCannotBeAChatIsDroppedWithoutNavigating() {
        target = NotificationNavigationTarget("agent-n", " ")
        compose.setContent { Workspace() }
        compose.runOnIdle {
            assertEquals(CurrentFocus(), workspace.currentFocus)
            assertEquals(1, consumed)
            assertNull(target)
        }
    }

    /** The production order in AndroidShellWorkspace: bind the launch's focus while composing, then run the launch effect. */
    @Composable
    private fun Workspace() {
        val owner = rememberShellWorkspaceState(ShellDestination.Home).also { workspace = it }
        val port = remember(owner) { AndroidShellChatPort(owner, { emptyList() }, { emptyList() }) }
        val launch = AndroidShellLaunch(target, { target = null; consumed++ }, {})
        BindLaunchFocus(launch, owner, graph, source) { bindAndroidFocus(owner, graph, source, port, drawer, config, it) }
        AndroidShellLaunchEffect(shell(owner, port, drawer), launch)
    }

    private fun shell(owner: ShellWorkspaceState, port: AndroidShellChatPort, drawer: SharedNavDrawerViewModel) =
        WorkspaceShell(
            config = config, workspace = owner, port = port, drawerModel = drawer,
            layout = mockk(relaxed = true), drawer = ShellWorkspaceDrawer(),
            exits = AndroidShellExits({ _, _, _ -> }, {}, SettingsDestinations()),
            chatPanel = mutableStateOf(null), controllers = mockk(relaxed = true),
        )

    private fun exact(agent: String, conversation: String) =
        CurrentFocus("chat", AgentId(agent), ConversationId(conversation), CurrentFocusChatMode.Exact)
}
