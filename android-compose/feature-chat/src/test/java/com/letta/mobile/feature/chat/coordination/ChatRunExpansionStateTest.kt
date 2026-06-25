package com.letta.mobile.feature.chat.coordination

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRunExpansionStateTest {

    @Test
    fun testHydrateUiState_restoresSavedState() {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                "collapsedRunIds" to arrayListOf("run1", "run2"),
                "expandedReasoningMessageIds" to arrayListOf("msg1")
            )
        )
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        expansionState.hydrateUiState()

        val currentState = uiStateFlow.value
        assertEquals(setOf("run1", "run2"), currentState.collapsedRunIds)
        assertEquals(setOf("msg1"), currentState.expandedReasoningMessageIds)
    }

    @Test
    fun testHydrateUiState_emptyState() {
        val savedStateHandle = SavedStateHandle()
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        expansionState.hydrateUiState()

        val currentState = uiStateFlow.value
        assertTrue(currentState.collapsedRunIds.isEmpty())
        assertTrue(currentState.expandedReasoningMessageIds.isEmpty())
    }

    @Test
    fun testToggleRunCollapsed_collapseRun() {
        val savedStateHandle = SavedStateHandle()
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        expansionState.toggleRunCollapsed("run1")

        val currentState = uiStateFlow.value
        assertTrue(currentState.collapsedRunIds.contains("run1"))

        val savedCollapsed = savedStateHandle.get<ArrayList<String>>("collapsedRunIds")
        assertTrue(savedCollapsed?.contains("run1") == true)

        val savedSuppressed = savedStateHandle.get<ArrayList<String>>("autoCollapseSuppressedRunIds")
        assertTrue(savedSuppressed.isNullOrEmpty())
    }

    @Test
    fun testToggleRunCollapsed_expandCollapsedRun() {
        val savedStateHandle = SavedStateHandle(
            mapOf("collapsedRunIds" to arrayListOf("run1"))
        )
        val uiStateFlow = MutableStateFlow(ChatUiState(collapsedRunIds = persistentSetOf("run1")))
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        expansionState.toggleRunCollapsed("run1")

        val currentState = uiStateFlow.value
        assertFalse(currentState.collapsedRunIds.contains("run1"))

        val savedCollapsed = savedStateHandle.get<ArrayList<String>>("collapsedRunIds")
        assertTrue(savedCollapsed.isNullOrEmpty())

        val savedSuppressed = savedStateHandle.get<ArrayList<String>>("autoCollapseSuppressedRunIds")
        assertTrue(savedSuppressed?.contains("run1") == true)
    }

    @Test
    fun testToggleReasoningExpanded() {
        val savedStateHandle = SavedStateHandle()
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        // Expand
        expansionState.toggleReasoningExpanded("msg1")
        assertTrue(uiStateFlow.value.expandedReasoningMessageIds.contains("msg1"))
        assertTrue(savedStateHandle.get<ArrayList<String>>("expandedReasoningMessageIds")?.contains("msg1") == true)

        // Collapse
        expansionState.toggleReasoningExpanded("msg1")
        assertFalse(uiStateFlow.value.expandedReasoningMessageIds.contains("msg1"))
        assertTrue(savedStateHandle.get<ArrayList<String>>("expandedReasoningMessageIds").isNullOrEmpty())
    }

    @Test
    fun testCollapseCompletedRunsIfStreamingFinished_streamingContinues() {
        val savedStateHandle = SavedStateHandle()
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        val previousState = ChatUiState(isStreaming = true)
        val nextState = ChatUiState(isStreaming = true)

        val resultState = expansionState.collapseCompletedRunsIfStreamingFinished(previousState, nextState)
        assertEquals(nextState, resultState)
    }

    @Test
    fun testCollapseCompletedRunsIfStreamingFinished_streamingFinished_withEligibleRuns() {
        val savedStateHandle = SavedStateHandle()
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        val previousState = ChatUiState(isStreaming = true)

        val messages = listOf(
            UiMessage(id = "1", role = "user", content = "hello", timestamp = "0"),
            UiMessage(id = "2", role = "assistant", content = "test", timestamp = "1", runId = "run1")
        )
        val nextState = ChatUiState(isStreaming = false, messages = kotlinx.collections.immutable.persistentListOf(*messages.toTypedArray()))

        val resultState = expansionState.collapseCompletedRunsIfStreamingFinished(previousState, nextState)

        assertTrue(resultState.collapsedRunIds.contains("run1"))
        assertTrue(savedStateHandle.get<ArrayList<String>>("collapsedRunIds")?.contains("run1") == true)
    }

    @Test
    fun testCollapseCompletedRunsIfStreamingFinished_streamingFinished_suppressedRun() {
        val savedStateHandle = SavedStateHandle(
            mapOf("autoCollapseSuppressedRunIds" to arrayListOf("run1"))
        )
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        val previousState = ChatUiState(isStreaming = true)

        val messages = listOf(
            UiMessage(id = "1", role = "user", content = "hello", timestamp = "0"),
            UiMessage(id = "2", role = "assistant", content = "test", timestamp = "1", runId = "run1")
        )
        val nextState = ChatUiState(isStreaming = false, messages = kotlinx.collections.immutable.persistentListOf(*messages.toTypedArray()))

        val resultState = expansionState.collapseCompletedRunsIfStreamingFinished(previousState, nextState)

        assertFalse(resultState.collapsedRunIds.contains("run1"))
    }
}
