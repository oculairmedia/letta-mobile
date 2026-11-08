package com.letta.mobile.ui.navigation

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode

/** Android host saved-state delivery through the production shared saver; no jobs or sends saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class AndroidFocusRecreationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedStateRestoresUnderlyingChatTabsEditorAndRail() {
        val restoration = StateRestorationTester(compose)
        lateinit var current: ShellWorkspaceState
        restoration.setContent {
            val workspace = rememberShellWorkspaceState(ShellDestination.Home)
            SideEffect { current = workspace }
        }
        lateinit var original: ShellWorkspaceState
        compose.runOnIdle {
            original = current
            current.bindSource("trusted-backend", 1L)
            current.navigate(ShellDestination.Chat("agent-b", "conversation-b"))
            current.tabs = ConversationTabsState(listOf("conversation-a", "conversation-b"), "conversation-preview")
            current.navigate(ShellDestination.AgentEditor("agent-editor"))
            current.railExpanded = true
        }

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            assertNotSame(original, current)
            assertEquals("trusted-backend", current.sourceKey)
            assertEquals(AgentId("agent-b"), current.currentFocus.agentId)
            assertEquals(ConversationId("conversation-b"), current.currentFocus.conversationId)
            assertEquals(listOf("conversation-a", "conversation-b"), current.tabs.pinnedConversationIds)
            assertEquals("conversation-preview", current.tabs.transientConversationId)
            assertEquals(ShellDestination.AgentEditor("agent-editor"), current.agentEditor)
            assertTrue(current.railExpanded)
            assertTrue(!current.accepts(original.requestToken()))
        }
    }

    @Test
    fun restoredNewChatDoesNotReplayItsInitialSend() {
        val restoration = StateRestorationTester(compose)
        lateinit var current: ShellWorkspaceState
        restoration.setContent {
            val workspace = rememberShellWorkspaceState(ShellDestination.Home)
            SideEffect { current = workspace }
        }
        compose.runOnIdle {
            current.bindSource("trusted-backend", 1L)
            current.selectFocus(CurrentFocus(
                "chat", AgentId("agent-b"), chatMode = CurrentFocusChatMode.New,
            ))
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val port = AndroidShellChatPort(current, { emptyList() }, { emptyList() })
            val opening = port.adopt(AgentId("agent-b"), null)
            assertNull(opening.firstMessage)
            assertNull(opening.fresh)
            assertNull(current.currentFocus.conversationId)
            assertEquals(CurrentFocusChatMode.New, current.currentFocus.chatMode)
        }
    }
}
