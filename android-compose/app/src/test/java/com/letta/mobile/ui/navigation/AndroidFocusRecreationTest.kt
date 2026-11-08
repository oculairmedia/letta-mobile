package com.letta.mobile.ui.navigation

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.shell.rememberShellWorkspaceState
import kotlinx.coroutines.cancel
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

/** Production saver delivery stays pending until the Android host validates its captured source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class AndroidFocusRecreationTest {
    @get:Rule val compose = createComposeRule()
    private val config = LettaConfig("config", LettaConfig.Mode.SELF_HOSTED, "https://a.invalid", accessToken = "principal-a")

    @Test
    fun savedStateRestoresUnderlyingChatTabsEditorAndRail() {
        val restoration = StateRestorationTester(compose)
        lateinit var current: ShellWorkspaceState
        restoration.setContent {
            val workspace = rememberShellWorkspaceState(ShellDestination.Home)
            SideEffect { current = workspace }
        }
        val graph = androidFocusGraph(config, 2L)
        val source = AndroidShellChatPort.sourceKey(graph)
        lateinit var original: ShellWorkspaceState
        compose.runOnIdle {
            original = current
            current.bindSource(source, 1L)
            current.navigate(ShellDestination.Chat("agent-b", "conversation-b"))
            current.tabs = ConversationTabsState(listOf("conversation-a", "conversation-b"), "conversation-preview")
            current.navigate(ShellDestination.AgentEditor("agent-editor"))
            current.railExpanded = true
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val pending = current.snapshotOrPending()
            val unboundFocus = current.currentFocus
            val unboundEditor = current.agentEditor
            val unboundSource = current.sourceKey
            val drawer = androidFocusDrawer(graph)
            try {
                val port = AndroidShellChatPort(current, { emptyList() }, { emptyList() })
                bindAndroidFocus(current, graph, source, port, drawer, config, null)
                assertNotSame(original, current)
                assertEquals(original.snapshot(), pending)
                assertNull(unboundSource)
                assertNull(unboundFocus.agentId)
                assertNull(unboundFocus.conversationId)
                assertNull(unboundEditor)
                assertEquals(source, current.sourceKey)
                assertEquals(2L, current.sourceGeneration)
                assertEquals(AgentId("agent-b"), current.currentFocus.agentId)
                assertEquals(ConversationId("conversation-b"), current.currentFocus.conversationId)
                assertEquals(listOf("conversation-a", "conversation-b"), current.tabs.pinnedConversationIds)
                assertEquals("conversation-preview", current.tabs.transientConversationId)
                assertEquals(ShellDestination.AgentEditor("agent-editor"), current.agentEditor)
                assertTrue(current.railExpanded)
                assertTrue(!current.accepts(original.requestToken()))
            } finally { drawer.viewModelScope.cancel() }
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
        val graph = androidFocusGraph(config, 2L)
        val source = AndroidShellChatPort.sourceKey(graph)
        lateinit var original: ShellWorkspaceState
        compose.runOnIdle {
            original = current
            current.bindSource(source, 1L)
            current.selectFocus(CurrentFocus("chat", AgentId("agent-b"), chatMode = CurrentFocusChatMode.New))
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val pending = current.snapshotOrPending()
            val unboundFocus = current.currentFocus
            val unboundSource = current.sourceKey
            val drawer = androidFocusDrawer(graph)
            val port = AndroidShellChatPort(current, { emptyList() }, { emptyList() })
            try {
                bindAndroidFocus(current, graph, source, port, drawer, config, null)
                assertEquals(original.snapshot(), pending)
                assertNull(unboundSource)
                assertNull(unboundFocus.agentId)
                assertNull(unboundFocus.conversationId)
                assertNull(port.opening)
                val opening = port.adopt(AgentId("agent-b"), null)
                assertNull(opening.firstMessage)
                assertNull(opening.fresh)
                assertNull(current.currentFocus.conversationId)
                assertEquals(CurrentFocusChatMode.New, current.currentFocus.chatMode)
                assertEquals(source, current.sourceKey)
                assertEquals(2L, current.sourceGeneration)
                assertTrue(!current.accepts(original.requestToken()))
            } finally { drawer.viewModelScope.cancel() }
        }
    }
}
