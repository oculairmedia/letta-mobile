package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.repository.api.FeatureFlag
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.MainDispatcherRule
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun viewModel() = SharedNavDrawerViewModel(settings, canvasStore, conversations)

    @Test
    fun followsTheSettingsFlag() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.enabled.value)
        settings.setFeatureFlag(FeatureFlag.SharedNavDrawer, true)
        advanceUntilIdle()
        assertTrue(vm.enabled.value)
    }

    @Test
    fun refreshReadsTheCanvasLibraryAndKeepsItOnFailure() = runTest(mainDispatcherRule.dispatcher) {
        val board = CanvasDocument(id = CanvasId("k1"), title = "Board")
        coEvery { canvasStore.listAll() } returns listOf(board)
        val vm = viewModel()
        vm.refreshCanvases()
        advanceUntilIdle()
        assertEquals(listOf(board), vm.canvases.value)

        coEvery { canvasStore.listAll() } throws IllegalStateException("offline")
        vm.refreshCanvases()
        advanceUntilIdle()
        assertEquals(listOf(board), vm.canvases.value)
    }

    @Test
    fun rowActionsReachTheConversationRepository() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.setArchiveFilter(ShellArchiveFilter.Archived)
        vm.setConversationArchived("c1", "agent-1", archived = true)
        vm.deleteConversation("c2", "agent-1")
        advanceUntilIdle()
        assertEquals(ShellArchiveFilter.Archived, vm.archiveFilter.value)
        coVerify { conversations.setConversationArchived("c1", "agent-1", true) }
        coVerify { conversations.deleteConversation("c2", "agent-1") }
    }

    @Test
    fun railPinsReachTheSettingsRepository() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedAgentIds.value)
        vm.setAgentPinned("agent-2", pinned = true)
        advanceUntilIdle()
        assertEquals(setOf("agent-2"), vm.pinnedAgentIds.value)
        vm.setAgentPinned("agent-2", pinned = false)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.pinnedAgentIds.value)
    }

    @Test
    fun sectionsNavigateToTheAndroidPages() {
        val calls = mutableListOf<String>()
        val navigation = AgentScaffoldNavigationCallbacks(
            onNavigateBack = {},
            onNavigateToSettings = {},
            onNavigateToMemory = { calls += "memory:$it" },
            onNavigateToSchedules = { calls += "schedules:$it" },
            onNavigateToTools = { calls += "tools" },
            onNavigateToConversationList = { calls += "conversations" },
        )
        LensDestination.entries.forEach { openSection(navigation, "agent-1", it) }
        assertEquals(listOf("memory:agent-1", "schedules:agent-1", "tools", "conversations"), calls)
        assertEquals(setOf(LensDestination.Channels), AndroidHiddenDrawerSections)
    }
}
