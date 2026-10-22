package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.testutil.FakeConversationRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.testutil.MainDispatcherRule
import com.letta.mobile.ui.shell.ShellNavDrawerInput
import com.letta.mobile.ui.shell.ShellNavDrawerMapping
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag

/** The Android binding of the shared navigation drawer (letta-mobile-c3np7.5.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class SharedNavDrawerBindingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val settings = FakeSettingsRepository()
    private val canvasStore: CanvasDocumentStore = mockk(relaxed = true)
    private val conversations: IConversationRepository = mockk(relaxed = true)
    private val fleetConversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val allConversations: IAllConversationsRepository = mockk(relaxed = true) {
        every { this@mockk.conversations } returns fleetConversations
    }

    private val now = Instant.parse("2026-10-17T12:00:00Z")

    private fun viewModel(repository: IConversationRepository = conversations) =
        SharedNavDrawerViewModel(settings, canvasStore, allConversations, InMemorySecureSettingsStore(), sources(repository))

    private fun sources(repository: IConversationRepository): SharedNavDrawerSources = mockk(relaxed = true) {
        every { this@mockk.conversations } returns repository
    }

    @Test
    fun refreshReadsTheCanvasLibraryAndKeepsItOnFailure() = runTest(mainDispatcherRule.dispatcher) {
        val board = CanvasDocument(id = CanvasId("k1"), title = "Board")
        coEvery { canvasStore.listAll() } returns listOf(board)
        val vm = viewModel()
        vm.refresh(agent = null)
        advanceUntilIdle()
        assertEquals(listOf(board), vm.canvases.value)

        coEvery { canvasStore.listAll() } throws IllegalStateException("offline")
        vm.refresh(agent = null)
        advanceUntilIdle()
        assertEquals(listOf(board), vm.canvases.value)
    }

    @Test
    fun rowActionsReachTheConversationRepository() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.setArchiveFilter(ShellArchiveFilter.Archived)
        vm.setConversationArchived(ConversationId("c1"), AgentId("agent-1"), archived = true)
        vm.deleteConversation(ConversationId("c2"), AgentId("agent-1"))
        advanceUntilIdle()
        assertEquals(ShellArchiveFilter.Archived, vm.archiveFilter.value)
        coVerify { conversations.setConversationArchived("c1", "agent-1", true) }
        coVerify { conversations.deleteConversation("c2", "agent-1") }
    }

    @Test
    fun conversationRenameReachesTheRepositoryTrimmedAndIgnoresBlank() = runTest(mainDispatcherRule.dispatcher) {
        val agent = AgentId("agent-1")
        val fake = FakeConversationRepository(
            listOf(
                Conversation(id = ConversationId("c1"), agentId = agent, summary = "Old"),
                Conversation(id = ConversationId("c2"), agentId = agent, summary = "Keep"),
            ),
        )
        val vm = viewModel(fake)
        vm.renameConversation(ConversationId("c1"), agent, ConversationSummary("  Plan B  "))
        vm.renameConversation(ConversationId("c2"), agent, ConversationSummary("   "))
        advanceUntilIdle()
        assertEquals(listOf("Plan B", "Keep"), fake.getCachedConversations(agent).map { it.summary })
    }

    @Test
    fun conversationPinsRoundTripThroughTheSettingsStoreAndOrderTheRows() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.pinnedConversationIds.collect {} }
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedConversationIds.value)
        vm.setConversationPinned(ConversationId("c2"), pinned = true)
        advanceUntilIdle()
        assertEquals(setOf("c2"), vm.pinnedConversationIds.value)

        val input = ShellNavDrawerInput(
            agent = ShellPanelAgent(name = "Agent 1", agentId = "a1"),
            conversations = listOf(conversation("c1", "a1", "2026-10-17T11:00:00Z"), conversation("c2", "a1", "2026-10-17T10:00:00Z")),
            pinnedConversationIds = vm.pinnedConversationIds.value,
        )
        val rows = ShellNavDrawerMapping.state(input, now).panel.conversations
        assertEquals(listOf("c2", "c1"), rows.map { it.id })
        assertEquals(listOf(true, false), rows.map { it.pinned })

        vm.setConversationPinned(ConversationId("c2"), pinned = false)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedConversationIds.value)
    }

    @Test
    fun aFailedConversationActionIsSurfacedNotSwallowed() = runTest(mainDispatcherRule.dispatcher) {
        val failing: IConversationRepository = mockk(relaxed = true) {
            coEvery { updateConversation(ConversationId("c1"), AgentId("agent-1"), "Name") } throws IllegalStateException("offline")
        }
        val vm = viewModel(failing)
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.failures.collect { messages += it } }
        advanceUntilIdle()
        vm.renameConversation(ConversationId("c1"), AgentId("agent-1"), ConversationSummary("Name"))
        advanceUntilIdle()
        assertEquals(listOf("Couldn't rename the chat"), messages)
    }

    @Test
    fun aChatsViewModelStoreOutlivesItsPageUntilReleased() {
        val vm = viewModel()
        val store = vm.chatStore("a:c")
        val model = object : androidx.lifecycle.ViewModel() {}
        store.put("chat", model)
        // A rotation asks again for the same chat and finds the same store.
        assertEquals(store, vm.chatStore("a:c"))
        vm.releaseChat("a:c")
        assertEquals(null, store["chat"])
        assertFalse(vm.chatStore("a:c") === store)
    }

    @Test
    fun deleteIsPermanentExceptWhereTheBackendOnlyArchives() {
        assertEquals(ConversationDeleteBehavior.Permanent, viewModel().deleteBehavior)
    }

    @Test
    fun railPinsReachTheSettingsRepository() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.pinnedAgentIds.collect {} }
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedAgentIds.value)
        vm.setAgentPinned(AgentId("agent-2"), pinned = true)
        advanceUntilIdle()
        assertEquals(setOf("agent-2"), vm.pinnedAgentIds.value)
        vm.setAgentPinned(AgentId("agent-2"), pinned = false)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedAgentIds.value)
    }

    @Test
    fun eachAgentsNewestConversationIsItsActivity() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.agentActivity.collect {} }
        fleetConversations.value = listOf(
            conversation("c1", "a7", lastMessageAt = "2026-10-17T11:00:00Z"),
            conversation("c2", "a5", lastMessageAt = "2026-10-17T10:00:00Z"),
            conversation("c3", "a7", lastMessageAt = "2026-10-10T10:00:00Z"),
        )
        advanceUntilIdle()
        assertEquals(
            mapOf("a7" to Instant.parse("2026-10-17T11:00:00Z"), "a5" to Instant.parse("2026-10-17T10:00:00Z")),
            vm.agentActivity.value,
        )
    }

    @Test
    fun openingTheDrawerRefreshesTheFleetConversationsAndSurvivesFailure() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { allConversations.refreshIfStale(any()) } throws IllegalStateException("offline")
        val vm = viewModel()
        backgroundScope.launch { vm.agentActivity.collect {} }
        vm.refresh(agent = null)
        advanceUntilIdle()
        coVerify { allConversations.refreshIfStale(any()) }
        // The failed refresh leaves the rail's activity as it was.
        assertEquals(emptyMap<String, Instant>(), vm.agentActivity.value)
    }

    private fun conversation(id: String, agentId: String, lastMessageAt: String) =
        Conversation(id = ConversationId(id), agentId = AgentId(agentId), lastMessageAt = lastMessageAt)}
