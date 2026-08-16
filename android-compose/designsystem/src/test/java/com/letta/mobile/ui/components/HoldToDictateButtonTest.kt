package com.letta.mobile.ui.components.audio

import android.Manifest
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class HoldToDictateButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun grantRecordAudioPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun interactionTargetRemains48DpAround44DpVisual() {
        composeRule.setContent {
            HoldToDictateButton(
                isRecognizing = false,
                onStart = {},
                onStop = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithTag(HOLD_TO_DICTATE_BUTTON_TEST_TAG)
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
    }
}
