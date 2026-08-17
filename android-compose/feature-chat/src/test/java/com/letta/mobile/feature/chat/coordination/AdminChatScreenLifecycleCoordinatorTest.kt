package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.chat.runtime.ChatSessionState
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminChatScreenLifecycleCoordinatorTest {

    private class Harness(
        isAlreadyHydrated: () -> Boolean,
        initialState: ChatSessionState = ChatSessionState(),
    ) {
        val sessionState = MutableStateFlow(initialState)
        var resolveCalls = 0
        var resumeSyncCalls = 0

        val coordinator = AdminChatScreenLifecycleCoordinator(
            currentConversationTracker = CurrentConversationTracker(),
            conversationId = { ConversationId("conversation-1") },
            sessionState = sessionState,
            resolveConversationAndLoad = { resolveCalls++ },
            updateSessionState = { reducer -> sessionState.value = reducer(sessionState.value) },
            isAlreadyHydrated = isAlreadyHydrated,
            triggerResumeSync = { resumeSyncCalls++ },
        )
    }

    @Test
    fun `resume skips the retryConnection wipe when messages are already on screen`() = runTest {
        val harness = Harness(
            isAlreadyHydrated = { true },
            initialState = ChatSessionState(connectionState = ChatConnectionState.StreamDisconnected),
        )

        harness.coordinator.onScreenResumed()

        // letta-mobile-6bqi1: rotation wipes the SESSION state via
        // retryConnection (selectionGeneration + 1); the hydrated guard must
        // prevent that wipe while still re-running resolveConversationAndLoad.
        assertEquals(0, harness.sessionState.value.selectionGeneration)
        assertEquals(ChatConnectionState.StreamDisconnected, harness.sessionState.value.connectionState)
    }

    @Test
    fun `resume still re-resolves when messages are already on screen`() = runTest {
        val harness = Harness(
            isAlreadyHydrated = { true },
            initialState = ChatSessionState(connectionState = ChatConnectionState.StreamDisconnected),
        )

        harness.coordinator.onScreenResumed()

        assertEquals(1, harness.resolveCalls)
    }

    @Test
    fun `resume wipes session state when messages are not on screen`() = runTest {
        val harness = Harness(
            isAlreadyHydrated = { false },
            initialState = ChatSessionState(connectionState = ChatConnectionState.Offline),
        )

        harness.coordinator.onScreenResumed()

        assertTrue(harness.sessionState.value.selectionGeneration > 0)
    }

    @Test
    fun `resume only wipes when connection is dead`() = runTest {
        val harness = Harness(
            isAlreadyHydrated = { false },
            initialState = ChatSessionState(connectionState = ChatConnectionState.Live),
        )

        harness.coordinator.onScreenResumed()

        assertEquals(0, harness.sessionState.value.selectionGeneration)
    }

    @Test
    fun `resume triggers resume sync when connection is live`() = runTest {
        val harness = Harness(
            isAlreadyHydrated = { false },
            initialState = ChatSessionState(connectionState = ChatConnectionState.Live),
        )

        harness.coordinator.onScreenResumed()

        assertEquals(1, harness.resumeSyncCalls)
        assertEquals(0, harness.resolveCalls)
    }

    @Test
    fun `resume does not trigger resume sync when connection is offline`() = runTest {
        val harness = Harness(
            isAlreadyHydrated = { false },
            initialState = ChatSessionState(connectionState = ChatConnectionState.Offline),
        )

        harness.coordinator.onScreenResumed()

        assertEquals(0, harness.resumeSyncCalls)
        assertEquals(1, harness.resolveCalls)
    }
}
