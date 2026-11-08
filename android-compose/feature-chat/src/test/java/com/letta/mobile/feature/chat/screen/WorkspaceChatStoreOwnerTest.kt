package com.letta.mobile.feature.chat.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.CurrentFocusInputs
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Retained Android ViewModel stores and jobs; these tests do not claim a device rotation. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceChatStoreOwnerTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private fun viewModel(): SharedNavDrawerViewModel {
        val all = mockk<IAllConversationsRepository> {
            every { conversations } returns MutableStateFlow(emptyList())
        }
        return SharedNavDrawerViewModel(
            FakeSettingsRepository(), mockk(relaxed = true), all,
            InMemorySecureSettingsStore(), mockk(relaxed = true),
        )
    }

    @Test
    fun pendingFreshChatAndItsResolvedRouteFindTheSameRetainedVmAfterRecreation() {
        val drawer = viewModel()
        val owner = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            selectFocus(CurrentFocus(
                "chat", AgentId("agent-a"), chatMode = CurrentFocusChatMode.New,
            ))
        }
        val fresh = AgentChatRoute("agent-a", freshRouteKey = 7L)
        val freshKey = WorkspaceChatStoreOwner.retain(owner, fresh, drawer)
        val chat = object : ViewModel() {}
        val job = chat.viewModelScope.launch { kotlinx.coroutines.awaitCancellation() }
        drawer.chatStore(freshKey).put("chat", chat)
        fun recreate() = ShellWorkspaceState().apply {
            bindSource("source", 1L)
            assertTrue(restore(owner.snapshot()))
        }

        val pendingKey = WorkspaceChatStoreOwner.retain(recreate(), fresh.copy(freshRouteKey = null), drawer)
        assertSame(chat, drawer.chatStore(pendingKey)["chat"])
        assertTrue(job.isActive)
        assertTrue(owner.completeFocus(owner.requestToken(), ConversationId("resolved"), AgentId("agent-a")))
        WorkspaceChatStoreOwner.retain(owner, fresh, drawer)
        owner.navigate(ShellDestination.AgentEditor("agent-editor"))
        val restored = recreate()
        val exact = fresh.copy(conversationId = "resolved", freshRouteKey = null)
        val exactKey = WorkspaceChatStoreOwner.retain(restored, exact, drawer)

        assertSame(chat, drawer.chatStore(exactKey)["chat"])
        assertTrue(job.isActive)
        assertEquals("agent-editor", restored.agentEditor?.agentId)
        drawer.releaseChat(exactKey)
        assertTrue(job.isCancelled)
        assertEquals(null, drawer.chatStore(freshKey)["chat"])
    }

    @Test
    fun backendSwitchClearsEveryAliasAndCancelsTheOldVmJob() {
        val drawer = viewModel()
        val owner = ShellWorkspaceState().apply {
            bindSource("source-a", 1L)
            navigate(ShellDestination.Chat("agent-a", "same-id"))
        }
        val route = AgentChatRoute("agent-a", conversationId = "same-id")
        val key = WorkspaceChatStoreOwner.retain(owner, route, drawer)
        val old = drawer.chatStore(key)
        val chat = object : ViewModel() {}
        val job = chat.viewModelScope.launch { kotlinx.coroutines.awaitCancellation() }
        old.put("chat", chat)
        owner.bindSource("source-b", 2L)
        WorkspaceChatStoreOwner.bindSource(drawer, "source-b")

        assertEquals(null, old["chat"])
        assertTrue(job.isCancelled)
        assertEquals(ShellDestination.Home, owner.page)
        assertEquals(null, owner.currentFocus.agentId)
        assertFalse(old === drawer.chatStore(key))
    }

    @Test
    fun consumedEntryIdentityIsNotReplayedAcrossAHolderRecreationOrSourceRebind() {
        val drawer = viewModel()
        val entry = CurrentFocusInputs("source")
        assertTrue(WorkspaceChatStoreOwner.claimInputs(drawer, entry))
        assertFalse(WorkspaceChatStoreOwner.claimInputs(drawer, entry))
        WorkspaceChatStoreOwner.bindSource(drawer, "source")
        assertFalse(WorkspaceChatStoreOwner.claimInputs(drawer, entry))
        assertTrue(WorkspaceChatStoreOwner.claimInputs(drawer, entry.copy()))
    }

    @Test
    fun unreadyBindingAndANewerLiveGenerationFenceTheRetiringOwner() {
        val drawer = viewModel()
        every { drawer.sources.sessions.current.id } returns 1L
        val owner = ShellWorkspaceState(ShellDestination.Chat("agent-a", "conversation-a")).apply { bindSource("source", 1L) }
        val context = mockk<android.content.Context>(relaxed = true)
        assertTrue(WorkspaceChatStoreOwner.claimOwner(drawer, owner, context, ready = false))
        assertFalse(WorkspaceChatStoreOwner.isCurrentOwner(drawer, owner))
        assertTrue(WorkspaceChatStoreOwner.claimOwner(drawer, owner, context, ready = true))
        assertTrue(WorkspaceChatStoreOwner.isCurrentOwner(drawer, owner))
        every { drawer.sources.sessions.current.id } returns 2L
        assertFalse(WorkspaceChatStoreOwner.isCurrentOwner(drawer, owner))
    }

    @Test
    fun staleDrawerMutationCannotReachTheNewSourcesFollowingRepository() = runTest {
        val drawer = viewModel()
        val newSourceRecords = mutableSetOf("same-id")
        val following = mockk<com.letta.mobile.data.repository.api.IConversationRepository> {
            coEvery { deleteConversation("same-id", "agent-a") } coAnswers { check(newSourceRecords.remove("same-id")) }
            coEvery { deleteConversation(ConversationId("same-id"), AgentId("agent-a")) } coAnswers {
                check(newSourceRecords.remove("same-id"))
            }
        }
        every { drawer.sources.conversations } returns following
        every { drawer.sources.sessions.current.id } returns 2L
        drawer.workspaceOwner = ShellWorkspaceState(ShellDestination.Chat("agent-a", "same-id")).apply { bindSource("source-a", 1L) }
        drawer.workspaceReady = true

        drawer.deleteConversation(ConversationId("same-id"), AgentId("agent-a"))
        advanceUntilIdle()

        assertEquals(setOf("same-id"), newSourceRecords)
        coVerify(exactly = 0) { following.deleteConversation(ConversationId("same-id"), AgentId("agent-a")) }
        coVerify(exactly = 0) { following.deleteConversation("same-id", "agent-a") }
    }

    @Test
    fun sourceChangeBeforeTheQueuedDrawerMutationStartsCannotRetargetIt() = runTest {
        val drawer = viewModel()
        var retired = false
        val captured = mockk<com.letta.mobile.data.repository.api.IConversationRepository> {
            coEvery { deleteConversation("same-id", "agent-a") } coAnswers {
                check(!retired) { "Captured session retired" }
            }
            coEvery { deleteConversation(ConversationId("same-id"), AgentId("agent-a")) } coAnswers {
                check(!retired) { "Captured session retired" }
            }
        }
        val newSourceRecords = mutableSetOf("same-id")
        val following = mockk<com.letta.mobile.data.repository.api.IConversationRepository> {
            coEvery { deleteConversation("same-id", "agent-a") } coAnswers { check(newSourceRecords.remove("same-id")) }
            coEvery { deleteConversation(ConversationId("same-id"), AgentId("agent-a")) } coAnswers {
                check(newSourceRecords.remove("same-id"))
            }
        }
        val graphA = mockk<com.letta.mobile.data.session.SessionGraph> {
            every { id } returns 1L
            every { conversationRepository } returns captured
        }
        val graphB = mockk<com.letta.mobile.data.session.SessionGraph> {
            every { id } returns 2L
            every { conversationRepository } returns following
        }
        val current = MutableStateFlow(graphA)
        every { drawer.sources.sessions.current } answers { current.value }
        every { drawer.sources.conversations } returns following
        drawer.workspaceOwner = ShellWorkspaceState(ShellDestination.Chat("agent-a", "same-id")).apply { bindSource("source-a", 1L) }
        drawer.workspaceReady = true

        drawer.deleteConversation(ConversationId("same-id"), AgentId("agent-a"))
        retired = true
        current.value = graphB
        advanceUntilIdle()

        assertEquals(setOf("same-id"), newSourceRecords)
        coVerify(exactly = 0) { following.deleteConversation(ConversationId("same-id"), AgentId("agent-a")) }
        coVerify(exactly = 0) { following.deleteConversation("same-id", "agent-a") }
        coVerify(exactly = 0) { captured.deleteConversation(ConversationId("same-id"), AgentId("agent-a")) }
        coVerify(exactly = 0) { captured.deleteConversation("same-id", "agent-a") }
    }

}
