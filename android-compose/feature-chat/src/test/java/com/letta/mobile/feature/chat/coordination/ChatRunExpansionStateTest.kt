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

    // endregion

    // region letta-mobile-ah1ng: per-run terminal reconciliation

    @Test
    fun testReconcile_completedRunFirstSeenViaHydrationDefaultsCollapsed() {
        // A completed run observed for the first time during hydration or a
        // reconnect must default collapsed even though no isStreaming edge
        // ever fired. The old global-edge implementation left it expanded.
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))
        expansionState.hydrateUiState()

        val hydrated = ChatUiState(
            isStreaming = false,
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "a1", runId = "run-hist", content = "settled long ago"),
            ),
        )

        val result = expansionState.reconcileCollapsedRunsOnProjection(ChatUiState(), hydrated)

        assertTrue(result.collapsedRunIds.contains("run-hist"))
        assertTrue(
            "collapse must persist so process restart keeps the run folded",
            savedStateHandle.get<ArrayList<String>>("collapsedRunIds")?.contains("run-hist") == true,
        )
    }

    @Test
    fun testReconcile_nothingToCollapseReturnsNextUnchanged() {
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        val next = ChatUiState(messages = persistentListOf(userMessage(id = "u1")))

        assertSame(next, expansionState.reconcileCollapsedRunsOnProjection(ChatUiState(), next))
    }

    @Test
    fun testReconcile_liveTerminalTransitionCollapsesOncePresenceClears() {
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        val streaming = ChatUiState(
            isStreaming = true,
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "r1", runId = "run-live", content = "thinking", isPending = true),
            ),
        )
        val duringStream = expansionState.reconcileCollapsedRunsOnProjection(streaming, streaming)
        assertFalse(duringStream.collapsedRunIds.contains("run-live"))

        val terminal = duringStream.copy(
            isStreaming = false,
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "r1", runId = "run-live", content = "final answer"),
            ),
        )
        val settled = expansionState.reconcileCollapsedRunsOnProjection(duringStream, terminal)

        assertTrue(settled.collapsedRunIds.contains("run-live"))
        assertTrue(
            savedStateHandle.get<ArrayList<String>>("collapsedRunIds")?.contains("run-live") == true,
        )
    }

    @Test
    fun testReconcile_terminalProjectionLandingAfterPresenceClearedStillCollapses() {
        // Presence cleared while the run's rows were still pending; the old
        // edge-based implementation consumed its single true->false transition
        // here and never collapsed when the terminal projection later landed.
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        val streaming = ChatUiState(
            isStreaming = true,
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "r1", runId = "run-x", content = "working", isPending = true),
            ),
        )
        val step1 = expansionState.reconcileCollapsedRunsOnProjection(streaming, streaming)
        assertFalse(step1.collapsedRunIds.contains("run-x"))

        // Presence drops early; rows still pending => run must stay open.
        val presenceCleared = step1.copy(isStreaming = false, isAgentTyping = false)
        val step2 = expansionState.reconcileCollapsedRunsOnProjection(step1, presenceCleared)
        assertFalse(step2.collapsedRunIds.contains("run-x"))

        // Terminal rows finally land with presence already idle.
        val terminalRows = step2.copy(
            messages = persistentListOf(
                userMessage(id = "u1"),
                assistantMessage(id = "r1", runId = "run-x", content = "done"),
            ),
        )
        val step3 = expansionState.reconcileCollapsedRunsOnProjection(step2, terminalRows)
        assertTrue(step3.collapsedRunIds.contains("run-x"))
    }

    @Test
    fun testReconcile_multipleRunsCollapseOnlyTerminalOnesAndNeverReopen() {
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        // Turn 1 settles while turn 2 streams: run-1 collapses, run-2 stays open.
        val mixed = ChatUiState(
            isStreaming = true,
            collapsedRunIds = persistentSetOf("run-0"),
            messages = persistentListOf(
                userMessage(id = "u0"),
                assistantMessage(id = "a0", runId = "run-0", content = "older settled"),
                assistantMessage(id = "a1", runId = "run-1", content = "answer one"),
                userMessage(id = "u1"),
                assistantMessage(id = "a2", runId = "run-2", content = "working", isPending = true),
            ),
        )
        val afterFirst = expansionState.reconcileCollapsedRunsOnProjection(mixed, mixed)
        assertTrue(afterFirst.collapsedRunIds.containsAll(setOf("run-0", "run-1")))
        assertFalse(afterFirst.collapsedRunIds.contains("run-2"))

        // Later emissions must not reopen prior runs once run-2 settles.
        val allSettled = afterFirst.copy(
            isStreaming = false,
            messages = persistentListOf(
                userMessage(id = "u0"),
                assistantMessage(id = "a0", runId = "run-0", content = "older settled"),
                assistantMessage(id = "a1", runId = "run-1", content = "answer one"),
                userMessage(id = "u1"),
                assistantMessage(id = "a2", runId = "run-2", content = "answer two"),
            ),
        )
        val final = expansionState.reconcileCollapsedRunsOnProjection(allSettled, allSettled)
        assertTrue(final.collapsedRunIds.containsAll(setOf("run-0", "run-1", "run-2")))
    }

    @Test
    fun testReconcile_activeNewestRunStaysOpenAcrossEmissions() {
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        var current = ChatUiState(
            isStreaming = true,
            messages = persistentListOf(userMessage(id = "u1")),
        )
        repeat(3) { frame ->
            current = current.copy(
                messages = persistentListOf(
                    userMessage(id = "u1"),
                    assistantMessage(id = "r$frame", runId = "run-live", content = "token $frame", isPending = true),
                ),
            )
            val next = expansionState.reconcileCollapsedRunsOnProjection(current, current)
            assertFalse("active newest run must stay open (frame $frame)", next.collapsedRunIds.contains("run-live"))
            current = next
        }
    }

    @Test
    fun testReconcile_suppressedCompletedRunStaysOpenOnHydration() {
        val savedStateHandle = SavedStateHandle(
            mapOf("autoCollapseSuppressedRunIds" to arrayListOf("run-s")),
        )
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        val hydrated = ChatUiState(
            isStreaming = false,
            messages = persistentListOf(assistantMessage(id = "a1", runId = "run-s", content = "done")),
        )
        val result = expansionState.reconcileCollapsedRunsOnProjection(hydrated, hydrated)
        assertFalse(result.collapsedRunIds.contains("run-s"))
    }

    @Test
    fun testUserExpandedCompletedRunIsNotRecollapsedByLaterEmissions() {
        val savedStateHandle = SavedStateHandle()
        val uiStateFlow = MutableStateFlow(ChatUiState())
        val expansionState = ChatRunExpansionState(savedStateHandle, uiStateFlow)

        val terminal = ChatUiState(
            isStreaming = false,
            messages = persistentListOf(assistantMessage(id = "a1", runId = "run-1", content = "done")),
        )

        // Auto-collapse on first observation...
        val collapsedOnce = expansionState.reconcileCollapsedRunsOnProjection(terminal, terminal)
        assertTrue(collapsedOnce.collapsedRunIds.contains("run-1"))

        // ...user expands it again...
        uiStateFlow.value = collapsedOnce
        expansionState.toggleRunCollapsed("run-1")
        assertFalse(uiStateFlow.value.collapsedRunIds.contains("run-1"))

        // ...and every subsequent publication honours the suppression.
        repeat(3) {
            val result = expansionState.reconcileCollapsedRunsOnProjection(uiStateFlow.value, terminal.copy())
            assertFalse(result.collapsedRunIds.contains("run-1"))
        }
    }

    @Test
    fun testReconcile_streamingEdgeStillCollapsesForBackwardsCompatibility() {
        // The historical true->false streaming edge remains a valid collapse
        // trigger under reconciliation.
        val savedStateHandle = SavedStateHandle()
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        val previousState = ChatUiState(isStreaming = true)
        val nextState = ChatUiState(
            isStreaming = false,
            messages = persistentListOf(
                UiMessage(id = "1", role = "user", content = "hello", timestamp = "0"),
                UiMessage(id = "2", role = "assistant", content = "test", timestamp = "1", runId = "run1"),
            ),
        )

        val resultState = expansionState.reconcileCollapsedRunsOnProjection(previousState, nextState)
        assertTrue(resultState.collapsedRunIds.contains("run1"))
        assertTrue(savedStateHandle.get<ArrayList<String>>("collapsedRunIds")?.contains("run1") == true)
    }

    @Test
    fun testReconcile_suppressedRunSurvivesStreamingEdge() {
        val savedStateHandle = SavedStateHandle(
            mapOf("autoCollapseSuppressedRunIds" to arrayListOf("run1")),
        )
        val expansionState = ChatRunExpansionState(savedStateHandle, MutableStateFlow(ChatUiState()))

        val previousState = ChatUiState(isStreaming = true)
        val nextState = ChatUiState(
            isStreaming = false,
            messages = persistentListOf(
                UiMessage(id = "1", role = "user", content = "hello", timestamp = "0"),
                UiMessage(id = "2", role = "assistant", content = "test", timestamp = "1", runId = "run1"),
            ),
        )

        val resultState = expansionState.reconcileCollapsedRunsOnProjection(previousState, nextState)
        assertFalse(resultState.collapsedRunIds.contains("run1"))
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
