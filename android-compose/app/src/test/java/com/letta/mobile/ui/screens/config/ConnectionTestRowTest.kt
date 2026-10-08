package com.letta.mobile.ui.screens.config

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.transport.appserver.AppServerIdentity
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.ui.test.setLettaTestContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** letta-mobile-bzvro.1 (F01): the server card shows the identity on success and the reason on failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class ConnectionTestRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun successShowsTheServerIdentity() {
        composeRule.setLettaTestContent {
            ConnectionTestRow(
                state = ConnectionTestUiState.Finished(AppServerProbeResult.Ok(AppServerIdentity("local", "0.33.6", 1))),
                enabled = true,
                onTestConnection = {},
            )
        }

        composeRule.onNodeWithText("Connected: letta-code 0.33.6 (local backend, protocol 1)").assertExists()
    }

    @Test
    fun authenticationFailureSaysTheTokenWasRejected() {
        composeRule.setLettaTestContent {
            ConnectionTestRow(
                state = ConnectionTestUiState.Finished(AppServerProbeResult.Authentication("HTTP 401")),
                enabled = true,
                onTestConnection = {},
            )
        }

        composeRule.onNodeWithText("The server rejected this token. HTTP 401").assertExists()
    }

    @Test
    fun runningDisablesTheButtonAndIdleRunsTheTest() {
        var clicks = 0
        val state = mutableStateOf<ConnectionTestUiState>(ConnectionTestUiState.Running)
        composeRule.setLettaTestContent {
            ConnectionTestRow(state = state.value, enabled = true, onTestConnection = { clicks += 1 })
        }
        composeRule.onNodeWithTag(CONNECTION_TEST_BUTTON_TAG).assertIsNotEnabled()

        state.value = ConnectionTestUiState.Idle
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CONNECTION_TEST_BUTTON_TAG).performClick()

        assertEquals(1, clicks)
    }
}
