package com.letta.mobile.feature.chat.coordination

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRunExpansionStateTest {

    // region hydration / toggles (unchanged contracts)

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

    // endregion

    // region stable live / hydration presentation

    @Test
    fun testReconcile_hydratedRunRemainsInline() {
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))
        val hydrated = ChatUiState(
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "a1", runId = "run-hist", content = "settled long ago"),
            ),
        )

        val result = expansionState.reconcileCollapsedRunsOnProjection(ChatUiState(), hydrated)

        assertSame(hydrated, result)
        assertTrue(result.collapsedRunIds.isEmpty())
        assertTrue(savedStateHandle.get<ArrayList<String>>("collapsedRunIds").isNullOrEmpty())
    }

    @Test
    fun testReconcile_terminalTransitionPreservesLivePresentation() {
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))
        val streaming = ChatUiState(
            isStreaming = true,
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "a1", runId = "run-live", content = "working", isPending = true),
            ),
        )
        val terminal = streaming.copy(
            isStreaming = false,
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "a1", runId = "run-live", content = "done"),
            ),
        )

        val result = expansionState.reconcileCollapsedRunsOnProjection(streaming, terminal)

        assertSame(terminal, result)
        assertTrue(result.collapsedRunIds.isEmpty())
    }

    @Test
    fun testReconcile_preservesExplicitUserCollapse() {
        val savedStateHandle = SavedStateHandle(mapOf("collapsedRunIds" to arrayListOf("run-1")))
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))
        val hydrated = ChatUiState(
            collapsedRunIds = persistentSetOf("run-1"),
            messages = persistentListOf(
                assistantMessage(id = "a1", runId = "run-1", content = "done"),
            ),
        )

        val result = expansionState.reconcileCollapsedRunsOnProjection(ChatUiState(), hydrated)

        assertSame(hydrated, result)
        assertTrue(result.collapsedRunIds.contains("run-1"))
    }

    // endregion

    private fun userMessage(id: String) = UiMessage(
        id = id,
        role = "user",
        content = "hello",
        timestamp = "2026-05-02T12:00:00Z",
    )

    private fun assistantMessage(
        id: String,
        runId: String?,
        content: String,
        isPending: Boolean = false,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2026-05-02T12:00:01Z",
        runId = runId,
        isPending = isPending,
    )
}
