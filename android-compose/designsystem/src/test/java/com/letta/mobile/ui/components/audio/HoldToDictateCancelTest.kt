package com.letta.mobile.ui.components.audio

import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * letta-mobile-wlo08: an ACTION_CANCEL mid-hold reaches the detector as a release Compose has
 * already consumed. It used to commit the dictation (onStop); it must cancel instead. A button
 * that leaves composition mid-hold must cancel too, or the recognizer keeps recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class HoldToDictateCancelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var starts = 0
    private var stops = 0
    private var cancels = 0
    private var shown by mutableStateOf(true)

    @Before
    fun grantRecordAudioPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun setButton() {
        composeRule.setContent {
            if (shown) {
                HoldToDictateButton(
                    isRecognizing = false,
                    onStart = { starts++ },
                    onStop = { stops++ },
                    onCancel = { cancels++ },
                )
            }
        }
    }

    private fun hold(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        composeRule.onNodeWithTag(HOLD_TO_DICTATE_BUTTON_TEST_TAG).performTouchInput(block)
        composeRule.waitForIdle()
    }

    @Test
    fun releaseCommits() {
        setButton()
        hold {
            down(center)
            up()
        }
        assertEquals(listOf(1, 1, 0), listOf(starts, stops, cancels))
    }

    @Test
    fun actionCancelCancelsAndNeverCommits() {
        setButton()
        hold {
            down(center)
            cancel()
        }
        assertEquals(listOf(1, 0, 1), listOf(starts, stops, cancels))
    }

    @Test
    fun slideUpCancelsOnceAndTheReleaseIsANoOp() {
        setButton()
        hold {
            down(center)
            moveBy(Offset(0f, -SLIDE_PX))
            up()
        }
        assertEquals(listOf(1, 0, 1), listOf(starts, stops, cancels))
    }

    @Test
    fun leavingCompositionMidHoldCancels() {
        setButton()
        hold { down(center) }
        assertEquals(1, starts)

        shown = false
        composeRule.waitForIdle()

        assertEquals(0, stops)
        assertEquals(1, cancels)
    }

    @Test
    fun detectorRearmsAfterCancel() {
        setButton()
        hold {
            down(center)
            cancel()
        }
        hold {
            down(center)
            up()
        }
        assertEquals(listOf(2, 1, 1), listOf(starts, stops, cancels))
    }

    @Test
    fun consumedReleaseIsACancel() {
        assertEquals(DictationHoldEnd.Cancel, dictationReleaseOutcome(releaseConsumed = true))
        assertEquals(DictationHoldEnd.Commit, dictationReleaseOutcome(releaseConsumed = false))
    }

    private companion object {
        // Past the default 100dp threshold at any test density.
        const val SLIDE_PX = 2_000f
    }
}
