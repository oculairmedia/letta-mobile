package com.letta.mobile.ui.navigation

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.CurrentFocusInputs
import com.letta.mobile.data.shell.CurrentFocusSnapshot
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.runtime.BackendId
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFocusInputsTest {
    @Test
    fun externalEntryWinsBeforeLoadingWhileSavedTabsAndRailRemain() {
        val saved = CurrentFocusSnapshot(
            "source", exact(AgentId("saved"), ConversationId("saved-conversation")),
            pinnedConversationIds = listOf(ConversationId("saved-conversation")),
            editorRoute = "agent-editor?agent=editor", railExpanded = true,
        )
        val owner = ShellWorkspaceState().apply { bindSource("source", 1L); assertTrue(restore(saved)) }
        initializeAndroidFocus(owner, CurrentFocusInputs(
            "source", deepLink = CurrentFocusSnapshot("source", exact(AgentId("external"), ConversationId("external-conversation"))),
            explicitRoute = CurrentFocusSnapshot("source", CurrentFocus()), restored = saved,
            settingsSeed = CurrentFocusSnapshot("source", exact(AgentId("seed"), ConversationId("seed-conversation"))),
        ))
        assertEquals(exact(AgentId("external"), ConversationId("external-conversation")), owner.currentFocus)
        assertNull(owner.agentEditor)
        assertEquals(listOf("saved-conversation"), owner.tabs.pinnedConversationIds)
        assertTrue(owner.railExpanded)
    }

    @Test
    fun explicitHomeWinsOverRestorationAndARepeatedStartupCannotReseed() {
        val owner = ShellWorkspaceState().apply { bindSource("source", 1L) }
        initializeAndroidFocus(owner, CurrentFocusInputs(
            "source", explicitRoute = CurrentFocusSnapshot("source", CurrentFocus()),
            restored = CurrentFocusSnapshot("source", exact(AgentId("saved"), ConversationId("saved-conversation"))),
        ))
        assertEquals(CurrentFocus(), owner.currentFocus)
        owner.navigate(ShellDestination.Chat("user", "user-conversation"))
        initializeAndroidFocus(owner, CurrentFocusInputs(
            "source", settingsSeed = CurrentFocusSnapshot("source", exact(AgentId("seed"), ConversationId("seed-conversation"))),
        ))
        assertEquals(exact(AgentId("user"), ConversationId("user-conversation")), owner.currentFocus)
    }

    @Test
    fun foreignEntryCannotOverrideTheActiveSource() {
        val owner = ShellWorkspaceState().apply { bindSource("source", 1L); navigate(ShellDestination.Chat("user", "user-conversation")) }
        initializeAndroidFocus(owner, CurrentFocusInputs(
            "source", deepLink = CurrentFocusSnapshot("foreign", exact(AgentId("foreign"), ConversationId("same-id"))),
        ))
        assertEquals(exact(AgentId("user"), ConversationId("user-conversation")), owner.currentFocus)
    }

    @Test
    fun sourceNamespaceUsesCapturedBackendBindingAndIgnoresReconnectGeneration() {
        val remote = LettaConfig("config", LettaConfig.Mode.SELF_HOSTED, "https://endpoint-a.invalid", accessToken = "principal-a")
        val key = AndroidShellChatPort.sourceKey(graph(remote))
        assertEquals(key, AndroidShellChatPort.sourceKey(graph(remote, 2L)))
        assertNotEquals(key, AndroidShellChatPort.sourceKey(graph(remote.copy(serverUrl = "https://endpoint-b.invalid"))))
        assertNotEquals(key, AndroidShellChatPort.sourceKey(graph(remote.copy(accessToken = "principal-b"))))
        assertFalse(key.contains(remote.serverUrl))
        assertFalse(key.contains(checkNotNull(remote.accessToken)))
        val local = remote.copy(mode = LettaConfig.Mode.LOCAL, localModelPath = "model-a")
        assertEquals(
            AndroidShellChatPort.sourceKey(graph(local)),
            AndroidShellChatPort.sourceKey(graph(local.copy(localModelPath = "model-b"))),
        )
    }

    private fun exact(agent: AgentId, conversation: ConversationId) = CurrentFocus("chat", agent, conversation, CurrentFocusChatMode.Exact)
    private fun graph(config: LettaConfig, generation: Long = 1L) = mockk<SessionGraph> {
        every { id } returns generation
        every { capturedConfig } returns config
        every { backendDescriptor.backendId } returns BackendId("remote-letta:config")
    }
}
