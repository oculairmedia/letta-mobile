@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-ckmub.3.11: the Home connection hero, moved from desktop's ChatStatePanel. */
class HomeConnectionPanelTest {
    @Test
    fun failuresLeadWithTheProblemNotTheWordmark() {
        assertEquals(
            "Can't reach the backend",
            connectionFailureHeadline(ChatScreenStatus.BackendOffline(errorMessage = null), errorMessage = null),
        )
        assertEquals(
            "Message wasn't sent",
            connectionFailureHeadline(ChatScreenStatus.SendFailed(errorMessage = null), errorMessage = null),
        )
        assertEquals(
            "The server rejected the access token",
            connectionFailureHeadline(ChatScreenStatus.BackendOffline(null), "Authentication failed: HTTP 401"),
        )
        assertEquals(
            "This server is not a compatible App Server",
            connectionFailureHeadline(ChatScreenStatus.BackendOffline(null), "Incompatible server: HTTP 404"),
        )
    }

    @Test
    fun aCarriedErrorIsAFailureEvenWhenTheStatusReadsOrdinary() {
        assertEquals(
            "Something went wrong",
            connectionFailureHeadline(ChatScreenStatus.Ready(selectedConversationId = "c1", isSending = false), errorMessage = "boom"),
        )
    }

    @Test
    fun idleAndLoadingStatesKeepTheWordmark() {
        // Null headline == show the brand lockup; these are a welcome, not a fault.
        assertNull(connectionFailureHeadline(ChatScreenStatus.ConfigNeeded(errorMessage = null), errorMessage = null))
        assertNull(connectionFailureHeadline(ChatScreenStatus.NoConversations, errorMessage = null))
        assertNull(connectionFailureHeadline(ChatScreenStatus.Loading, errorMessage = null))
        assertNull(connectionFailureHeadline(ChatScreenStatus.Ready(selectedConversationId = "c1", isSending = true), errorMessage = null))
    }

    @Test
    fun anOfflineBackendOffersRetryAndShowsTheHeadline() = runComposeUiTest {
        var retries = 0
        setContent {
            MaterialTheme {
                HomeConnectionPanel(
                    state = HomeConnectionState(ChatScreenStatus.BackendOffline(errorMessage = null)),
                    onRetryConnection = { retries++ },
                    wordmark = "LETTA DESKTOP",
                )
            }
        }
        onNodeWithText("Can't reach the backend").assertExists()
        onNodeWithText("LETTA DESKTOP").assertDoesNotExist()
        onNodeWithTag(HomeConnectionPanelTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun retryWaitsForTheRunningAttempt() = runComposeUiTest {
        setContent {
            MaterialTheme {
                HomeConnectionPanel(
                    state = HomeConnectionState(ChatScreenStatus.BackendOffline(errorMessage = null), loading = true),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithTag(HomeConnectionPanelTags.RETRY).assertIsNotEnabled()
    }
}
