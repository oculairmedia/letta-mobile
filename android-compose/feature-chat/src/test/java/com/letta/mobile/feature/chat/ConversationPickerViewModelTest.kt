package com.letta.mobile.feature.chat

import com.letta.mobile.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class ConversationPickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository: com.letta.mobile.data.repository.ConversationRepository = mockk(relaxed = true)

    @Test
    fun toggleSelection_addsId() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)

        vm.toggleSelection("conv-1")
        val result = vm.selectedIds.first()

        assertTrue(result.contains("conv-1"))
    }

    @Test
    fun toggleSelection_removesId() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)

        vm.toggleSelection("conv-1")
        vm.toggleSelection("conv-1")
        val result = vm.selectedIds.first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun toggleSelection_supportsMultipleIds() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)

        vm.toggleSelection("conv-1")
        vm.toggleSelection("conv-2")
        vm.toggleSelection("conv-3")

        assertEquals(setOf("conv-1", "conv-2", "conv-3"), vm.selectedIds.first())
    }

    @Test
    fun clearSelection_emptiesSelected() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)

        vm.toggleSelection("conv-1")
        vm.toggleSelection("conv-2")
        vm.clearSelection()

        assertTrue(vm.selectedIds.first().isEmpty())
    }

    @Test
    fun deleteSelected_withEmptySelection_doesNotDeleteAnything() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)

        vm.deleteSelected(agentId = "agent-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.deleteConversation(any(), any()) }
    }

    @Test
    fun deleteSelected_deletesAllSelectedConversations() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)
        vm.toggleSelection("conv-1")
        vm.toggleSelection("conv-2")
        vm.toggleSelection("conv-3")

        vm.deleteSelected(agentId = "agent-abc")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteConversation("conv-1", "agent-abc") }
        coVerify(exactly = 1) { repository.deleteConversation("conv-2", "agent-abc") }
        coVerify(exactly = 1) { repository.deleteConversation("conv-3", "agent-abc") }
        assertTrue(vm.selectedIds.first().isEmpty())
    }

    @Test
    fun deleteSelected_clearsSelectionBeforeDeleteCompletes() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { repository.deleteConversation(any(), any()) } throws RuntimeException("boom")
        val vm = ConversationPickerViewModel(repository)
        vm.toggleSelection("conv-1")

        vm.deleteSelected(agentId = "agent-1")

        assertTrue(vm.selectedIds.first().isEmpty())
        advanceUntilIdle()
    }

    @Test
    fun deleteSelected_callsOnActiveDeletedWhenActiveConversationDeleted() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)
        vm.toggleSelection("active-conv")
        vm.toggleSelection("other-conv")
        var activeDeleted = false

        vm.deleteSelected(
            agentId = "agent-1",
            activeConversationId = "active-conv",
            onActiveDeleted = { activeDeleted = true },
        )
        advanceUntilIdle()

        assertTrue(activeDeleted)
    }

    @Test
    fun deleteSelected_doesNotCallOnActiveDeletedWhenActiveNotSelected() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ConversationPickerViewModel(repository)
        vm.toggleSelection("other-conv")
        var activeDeleted = false

        vm.deleteSelected(
            agentId = "agent-1",
            activeConversationId = "active-conv",
            onActiveDeleted = { activeDeleted = true },
        )
        advanceUntilIdle()

        assertTrue(!activeDeleted)
    }

    @Test
    fun deleteSelected_continuesWhenSingleDeleteFails() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { repository.deleteConversation("conv-2", any()) } throws RuntimeException("delete failed")
        val vm = ConversationPickerViewModel(repository)
        vm.toggleSelection("conv-1")
        vm.toggleSelection("conv-2")
        vm.toggleSelection("conv-3")

        vm.deleteSelected(agentId = "agent-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteConversation("conv-1", "agent-1") }
        coVerify(exactly = 1) { repository.deleteConversation("conv-2", "agent-1") }
        coVerify(exactly = 1) { repository.deleteConversation("conv-3", "agent-1") }
    }
}
