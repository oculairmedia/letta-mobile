package com.letta.mobile.ui.navigation

import androidx.lifecycle.viewModelScope
import com.letta.mobile.feature.chat.screen.SharedNavDrawerSources
import com.letta.mobile.feature.chat.screen.SharedNavDrawerViewModel
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.testutil.MainDispatcherRule
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule

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
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
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

    @Test
    fun newEditorEntryWinsOverPendingRestoreBeforeSourceBinding() {
        val config = LettaConfig("config-a", LettaConfig.Mode.SELF_HOSTED, "https://a.invalid", accessToken = "principal-a")
        val graph = graph(config)
        val source = AndroidShellChatPort.sourceKey(graph)
        val saved = CurrentFocusSnapshot(
            source, exact(AgentId("saved"), ConversationId("saved-conversation")),
            pinnedConversationIds = listOf(ConversationId("saved-conversation")), railExpanded = true,
        )
        val owner = ShellWorkspaceState().apply { assertTrue(holdPending(saved)) }
        val drawer = drawer(graph)
        try {
            bindAndroidFocus(owner, graph, source, AndroidShellChatPort(owner, { emptyList() }, { emptyList() }), drawer, config,
                CurrentFocusInputs(source, deepLink = CurrentFocusSnapshot(
                    source, exact(AgentId("external"), ConversationId("external-conversation")),
                    editorRoute = "agent-editor?agent=external-editor",
                )))
            assertEquals(exact(AgentId("external"), ConversationId("external-conversation")), owner.currentFocus)
            assertEquals(ShellDestination.AgentEditor("external-editor"), owner.agentEditor)
            assertEquals(listOf("saved-conversation"), owner.tabs.pinnedConversationIds)
            assertTrue(owner.railExpanded)
            assertEquals(graph.id, owner.sourceGeneration)
        } finally { drawer.viewModelScope.cancel() }
    }

    @Test
    fun pendingRestoreWaitsForTheCapturedBackendBeforeItCanPublishIdentity() {
        val oldConfig = LettaConfig("config-a", LettaConfig.Mode.SELF_HOSTED, "https://a.invalid", accessToken = "principal-a")
        val nextConfig = oldConfig.copy(id = "config-b", serverUrl = "https://b.invalid", accessToken = "principal-b")
        val oldGraph = graph(oldConfig)
        val nextGraph = graph(nextConfig, 2L)
        val source = AndroidShellChatPort.sourceKey(nextGraph)
        val saved = CurrentFocusSnapshot(
            source, exact(AgentId("next"), ConversationId("next-conversation")),
            pinnedConversationIds = listOf(ConversationId("next-conversation")), editorRoute = "agent-editor?agent=next-editor",
        )
        val owner = ShellWorkspaceState().apply { assertTrue(holdPending(saved)) }
        val port = AndroidShellChatPort(owner, { emptyList() }, { emptyList() })
        val drawer = drawer(oldGraph)
        try {
            bindAndroidFocus(owner, oldGraph, AndroidShellChatPort.sourceKey(oldGraph, nextConfig), port, drawer, nextConfig, null)
            assertNull(owner.sourceKey)
            assertEquals(CurrentFocus(), owner.currentFocus)
            assertEquals(saved, owner.snapshotOrPending())
            every { drawer.sources.sessions.current } returns nextGraph
            bindAndroidFocus(owner, nextGraph, source, port, drawer, nextConfig, null)
            assertEquals(saved.focus, owner.currentFocus)
            assertEquals(ShellDestination.AgentEditor("next-editor"), owner.agentEditor)
            assertEquals(listOf("next-conversation"), owner.tabs.pinnedConversationIds)
            assertEquals(2L, owner.sourceGeneration)
        } finally { drawer.viewModelScope.cancel() }
    }

    private fun drawer(graph: SessionGraph): SharedNavDrawerViewModel {
        val all = mockk<IAllConversationsRepository> {
            every { conversations } returns MutableStateFlow(emptyList())
        }
        val sources = mockk<SharedNavDrawerSources>(relaxed = true) {
            every { agents.agents } returns MutableStateFlow(emptyList())
            every { sessions.current } returns graph
        }
        return SharedNavDrawerViewModel(
            FakeSettingsRepository(), mockk(relaxed = true), all, InMemorySecureSettingsStore(), sources,
        )
    }

    private fun exact(agent: AgentId, conversation: ConversationId) = CurrentFocus("chat", agent, conversation, CurrentFocusChatMode.Exact)
    private fun graph(config: LettaConfig, generation: Long = 1L) = mockk<SessionGraph> {
        every { id } returns generation
        every { capturedConfig } returns config
        every { backendDescriptor.backendId } returns BackendId("remote-letta:config")
    }
}
