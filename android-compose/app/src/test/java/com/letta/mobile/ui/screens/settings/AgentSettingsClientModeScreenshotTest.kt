package com.letta.mobile.ui.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("screenshot")
class AgentSettingsClientModeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clientModeCollapsedWhenOff() {
        renderClientModeSection(sampleState(clientModeEnabled = false))
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun clientModeExpandedWithEmptyFields() {
        renderClientModeSection(sampleState(clientModeEnabled = true))
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun clientModeExpandedWithSuccessState() {
        renderClientModeSection(
            sampleState(
                clientModeEnabled = true,
                clientModeBaseUrl = "http://192.168.50.90:8407",
                clientModeApiKey = "secret-key",
                clientModeConnectionState = ClientModeConnectionState.Success(testedAtMillis = 1_714_000_000_000),
            )
        )
        composeRule.onRoot().captureRoboImage()
    }

    private fun renderClientModeSection(state: AgentSettingsUiState) {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ClientModeSettingsSection(
                    state = state,
                    onClientModeEnabledChange = {},
                    onClientModeBaseUrlChange = {},
                    onClientModeApiKeyChange = {},
                    onTestClientModeConnection = {},
                )
            }
        }
    }

    private fun sampleState(
        clientModeEnabled: Boolean,
        clientModeBaseUrl: String = "",
        clientModeApiKey: String = "",
        clientModeConnectionState: ClientModeConnectionState = ClientModeConnectionState.Idle,
    ) = AgentSettingsUiState(
        clientModeEnabled = clientModeEnabled,
        clientModeBaseUrl = clientModeBaseUrl,
        clientModeApiKey = clientModeApiKey,
        clientModeConnectionState = clientModeConnectionState,
    )
}
