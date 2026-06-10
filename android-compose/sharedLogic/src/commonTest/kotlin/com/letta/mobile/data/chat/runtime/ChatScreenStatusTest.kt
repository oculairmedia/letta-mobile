package com.letta.mobile.data.chat.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that [chatScreenStatusOf] produces the expected [ChatScreenStatus] variant
 * for every meaningful [ChatSessionState] + [ChatConnectionState] combination, and
 * that the extension helpers ([isError], [isConnectionRetryable], [shouldShowStatePanel])
 * agree with [ChatSessionReducer].
 *
 * Follows the style of [ChatSessionReducerTest]: small, focused tests using plain
 * [ChatSessionState] factories rather than mocks.
 */
class ChatScreenStatusTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun stateWith(
        connectionState: ChatConnectionState,
        selectedConversationId: String? = null,
        errorMessage: String? = null,
        isSending: Boolean = false,
        isLoading: Boolean = false,
    ): ChatSessionState = ChatSessionState(
        connectionState = connectionState,
        selectedConversationId = selectedConversationId,
        errorMessage = errorMessage,
        isSending = isSending,
        isLoading = isLoading,
        isRemoteBacked = connectionState != ChatConnectionState.ConfigNeeded,
    )

    // ------------------------------------------------------------------
    // NoConversations
    // ------------------------------------------------------------------

    @Test
    fun noConversationsStateProducesNoConversationsStatus() {
        val state = stateWith(ChatConnectionState.NoConversations)

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.NoConversations>(status)
        assertFalse(status.isError)
        assertFalse(status.isConnectionRetryable)
    }

    @Test
    fun noConversationsAlwaysShowsStatePanel() {
        // NoConversations is in ChatSessionReducer.panelStates, so the panel
        // must be shown even when there is no selected conversation id.
        val state = stateWith(
            ChatConnectionState.NoConversations,
            selectedConversationId = null,
        )
        val status = chatScreenStatusOf(state)

        assertTrue(status.shouldShowStatePanel(state))
    }

    // ------------------------------------------------------------------
    // BackendOffline
    // ------------------------------------------------------------------

    @Test
    fun offlineConnectionStateProducesBackendOfflineStatus() {
        val state = stateWith(
            ChatConnectionState.Offline,
            errorMessage = "Connection refused",
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.BackendOffline>(status)
        assertEquals("Connection refused", status.errorMessage)
        assertTrue(status.isError)
        assertTrue(status.isConnectionRetryable)
    }

    @Test
    fun backendOfflineWithNullErrorMessageIsPermitted() {
        val state = stateWith(ChatConnectionState.Offline)

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.BackendOffline>(status)
        assertNull(status.errorMessage)
    }

    @Test
    fun backendOfflineAlwaysShowsStatePanel() {
        val state = stateWith(ChatConnectionState.Offline, errorMessage = "down")

        val status = chatScreenStatusOf(state)

        assertTrue(status.shouldShowStatePanel(state))
    }

    // ------------------------------------------------------------------
    // ConfigNeeded
    // ------------------------------------------------------------------

    @Test
    fun configNeededConnectionStateProducesConfigNeededStatus() {
        val state = stateWith(
            ChatConnectionState.ConfigNeeded,
            errorMessage = "Set a server URL in Settings.",
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.ConfigNeeded>(status)
        assertEquals("Set a server URL in Settings.", status.errorMessage)
        assertTrue(status.isError)
        assertTrue(status.isConnectionRetryable)
    }

    @Test
    fun configNeededAlwaysShowsStatePanel() {
        val state = stateWith(ChatConnectionState.ConfigNeeded)

        val status = chatScreenStatusOf(state)

        assertTrue(status.shouldShowStatePanel(state))
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    @Test
    fun loadingConnectionStateProducesLoadingStatus() {
        val state = stateWith(ChatConnectionState.Loading, isLoading = true)

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.Loading>(status)
        assertFalse(status.isError)
        assertFalse(status.isConnectionRetryable)
    }

    @Test
    fun loadingAlwaysShowsStatePanel() {
        val state = stateWith(ChatConnectionState.Loading)

        val status = chatScreenStatusOf(state)

        assertTrue(status.shouldShowStatePanel(state))
    }

    // ------------------------------------------------------------------
    // SendFailed
    // ------------------------------------------------------------------

    @Test
    fun sendFailedConnectionStateProducesSendFailedStatus() {
        val state = stateWith(
            ChatConnectionState.SendFailed,
            selectedConversationId = "conv-1",
            errorMessage = "Network timeout",
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.SendFailed>(status)
        assertEquals("Network timeout", status.errorMessage)
        assertTrue(status.isError)
        // SendFailed is NOT a connection-level retry — the user retries via the composer
        assertFalse(status.isConnectionRetryable)
    }

    @Test
    fun sendFailedWithNullErrorIsPermitted() {
        val state = stateWith(ChatConnectionState.SendFailed)

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.SendFailed>(status)
        assertNull(status.errorMessage)
    }

    // ------------------------------------------------------------------
    // Ready (Live / Sending / StreamDisconnected / Demo)
    // ------------------------------------------------------------------

    @Test
    fun liveConnectionStateProducesReadyStatus() {
        val state = stateWith(
            ChatConnectionState.Live,
            selectedConversationId = "conv-abc",
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.Ready>(status)
        assertEquals("conv-abc", status.selectedConversationId)
        assertFalse(status.isSending)
        assertFalse(status.isError)
        assertFalse(status.isConnectionRetryable)
    }

    @Test
    fun sendingConnectionStateProducesReadyStatusWithIsSendingTrue() {
        val state = stateWith(
            ChatConnectionState.Sending,
            selectedConversationId = "conv-abc",
            isSending = true,
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.Ready>(status)
        assertTrue(status.isSending)
    }

    @Test
    fun streamDisconnectedProducesReadyStatus() {
        // StreamDisconnected keeps existing messages visible; the UI should
        // stay in message-list mode rather than flipping to a full state panel.
        val state = stateWith(
            ChatConnectionState.StreamDisconnected,
            selectedConversationId = "conv-abc",
            errorMessage = "WebSocket dropped",
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.Ready>(status)
        assertEquals("conv-abc", status.selectedConversationId)
    }

    @Test
    fun demoConnectionStateProducesReadyStatus() {
        val state = stateWith(
            ChatConnectionState.Demo,
            selectedConversationId = "demo-conv",
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.Ready>(status)
        assertEquals("demo-conv", status.selectedConversationId)
    }

    @Test
    fun readyWithNoSelectedConversationIdCarriesNullId() {
        // Possible immediately after transition from NoConversations
        val state = stateWith(
            ChatConnectionState.Live,
            selectedConversationId = null,
        )

        val status = chatScreenStatusOf(state)

        assertIs<ChatScreenStatus.Ready>(status)
        assertNull(status.selectedConversationId)
    }

    @Test
    fun readyLiveDoesNotShowStatePanelWhenConversationSelected() {
        val state = ChatSessionState(
            connectionState = ChatConnectionState.Live,
            selectedConversationId = "conv-xyz",
            isRemoteBacked = true,
        )

        val status = chatScreenStatusOf(state)

        assertFalse(status.shouldShowStatePanel(state))
    }

    // ------------------------------------------------------------------
    // isError / isConnectionRetryable completeness
    // ------------------------------------------------------------------

    @Test
    fun isErrorOnlyForErrorVariants() {
        val errored = listOf(
            chatScreenStatusOf(stateWith(ChatConnectionState.ConfigNeeded)),
            chatScreenStatusOf(stateWith(ChatConnectionState.Offline)),
            chatScreenStatusOf(stateWith(ChatConnectionState.SendFailed)),
        )
        val nonErrored = listOf(
            chatScreenStatusOf(stateWith(ChatConnectionState.Loading)),
            chatScreenStatusOf(stateWith(ChatConnectionState.NoConversations)),
            chatScreenStatusOf(stateWith(ChatConnectionState.Live)),
        )

        errored.forEach { assertTrue(it.isError, "Expected isError=true for $it") }
        nonErrored.forEach { assertFalse(it.isError, "Expected isError=false for $it") }
    }

    @Test
    fun isConnectionRetryableOnlyForConnectionErrors() {
        val retryable = listOf(
            chatScreenStatusOf(stateWith(ChatConnectionState.ConfigNeeded)),
            chatScreenStatusOf(stateWith(ChatConnectionState.Offline)),
        )
        val notRetryable = listOf(
            chatScreenStatusOf(stateWith(ChatConnectionState.SendFailed)),
            chatScreenStatusOf(stateWith(ChatConnectionState.Loading)),
            chatScreenStatusOf(stateWith(ChatConnectionState.NoConversations)),
            chatScreenStatusOf(stateWith(ChatConnectionState.Live)),
            chatScreenStatusOf(stateWith(ChatConnectionState.Sending)),
        )

        retryable.forEach { assertTrue(it.isConnectionRetryable, "Expected retryable for $it") }
        notRetryable.forEach { assertFalse(it.isConnectionRetryable, "Expected non-retryable for $it") }
    }

    // ------------------------------------------------------------------
    // shouldShowStatePanel parity with ChatSessionReducer
    // ------------------------------------------------------------------

    @Test
    fun statePanelShownForAllPanelStatesMatchesSessionReducer() {
        // The four states in ChatSessionReducer.panelStates must always show the panel
        val panelStates = listOf(
            ChatConnectionState.Loading,
            ChatConnectionState.ConfigNeeded,
            ChatConnectionState.Offline,
            ChatConnectionState.NoConversations,
        )
        panelStates.forEach { conn ->
            val state = stateWith(conn)
            val status = chatScreenStatusOf(state)
            assertTrue(
                status.shouldShowStatePanel(state),
                "Expected shouldShowStatePanel=true for $conn",
            )
        }
    }
}
