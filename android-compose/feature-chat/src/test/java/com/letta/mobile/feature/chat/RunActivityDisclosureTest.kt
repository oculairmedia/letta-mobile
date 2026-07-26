package com.letta.mobile.feature.chat

import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.feature.chat.screen.RunActivityDisclosure
import com.letta.mobile.feature.chat.screen.RunActivityDisclosureTestTags
import com.letta.mobile.feature.chat.screen.RunActivityProjection
import com.letta.mobile.feature.chat.screen.RunActivityState
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class RunActivityDisclosureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun workingStateIsQuietAndCannotBeCollapsed() {
        var toggles = 0
        setContent(
            activity = RunActivityProjection(
                state = RunActivityState.Working,
                durationMs = null,
                toolCount = 1,
                failureCount = 0,
            ),
            onToggle = { toggles++ },
        )

        composeRule.onNodeWithText("Working").assertIsDisplayed()
        composeRule.onNodeWithText("1 tool").assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                RunActivityDisclosureTestTags.WorkingIndicator,
                useUnmergedTree = true,
            )
            .assertExists()
        composeRule.onNodeWithTag(RunActivityDisclosureTestTags.Header)
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(48.dp)
            .assert(hasStateDescription("Agent work in progress"))
        composeRule.runOnIdle { assertEquals(0, toggles) }
    }

    @Test
    fun reducedMotionKeepsAStaticEquivalentOfTheWorkingCue() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val previousScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        Settings.Global.putFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        try {
            setContent(
                activity = RunActivityProjection(
                    state = RunActivityState.Working,
                    durationMs = null,
                    toolCount = 1,
                    failureCount = 0,
                ),
            )

            composeRule
                .onNodeWithTag(
                    RunActivityDisclosureTestTags.WorkingIndicator,
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeRule.onNodeWithTag(RunActivityDisclosureTestTags.Header)
                .assert(hasStateDescription("Agent work in progress"))
        } finally {
            Settings.Global.putFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                previousScale,
            )
        }
    }

    @Test
    fun thoughtStateShowsDurationCountsAndToggles() {
        var toggles = 0
        setContent(
            activity = RunActivityProjection(
                state = RunActivityState.Thought,
                durationMs = 2_400L,
                toolCount = 2,
                failureCount = 1,
            ),
            onToggle = { toggles++ },
        )

        composeRule.onNodeWithText("Thought for 2.4s").assertIsDisplayed()
        composeRule.onNodeWithText("2 tools").assertIsDisplayed()
        composeRule.onNodeWithText("1 failure").assertIsDisplayed()
        composeRule.onNodeWithTag(RunActivityDisclosureTestTags.Header)
            .assertHeightIsAtLeast(48.dp)
            .assert(hasStateDescription("Agent work expanded"))
            .performClick()
        composeRule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test
    fun completedCollapsedStateRemainsLegibleAndExposesExpansionState() {
        setContent(
            activity = RunActivityProjection(
                state = RunActivityState.Worked,
                durationMs = 3_200L,
                toolCount = 2,
                failureCount = 1,
            ),
            disclosure = DisclosureConfiguration(collapsed = true),
        )

        composeRule.onNodeWithText("Worked for 3.2s").assertIsDisplayed()
        composeRule.onNodeWithText("2 tools").assertIsDisplayed()
        composeRule.onNodeWithText("1 failure").assertIsDisplayed()
        composeRule.onNodeWithTag(RunActivityDisclosureTestTags.Header)
            .assert(hasStateDescription("Agent work collapsed"))
    }

    @Test
    fun toolOnlyStateUsesWorkedLabel() {
        setContent(
            activity = RunActivityProjection(
                state = RunActivityState.Worked,
                durationMs = 1_000L,
                toolCount = 3,
                failureCount = 0,
            ),
        )

        composeRule.onNodeWithText("Worked for 1.0s").assertIsDisplayed()
        composeRule.onNodeWithText("3 tools").assertIsDisplayed()
    }

    @Test
    fun singleStepDisclosureCannotHideItsOnlyMessage() {
        var toggles = 0
        setContent(
            activity = RunActivityProjection(
                state = RunActivityState.Worked,
                durationMs = 1_000L,
                toolCount = 0,
                failureCount = 0,
            ),
            disclosure = DisclosureConfiguration(collapsible = false),
            onToggle = { toggles++ },
        )

        composeRule.onNodeWithTag(RunActivityDisclosureTestTags.Header)
            .assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, toggles) }
    }

    private fun setContent(
        activity: RunActivityProjection,
        disclosure: DisclosureConfiguration = DisclosureConfiguration(),
        onToggle: () -> Unit = {},
    ) {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    RunActivityDisclosure(
                        activity = activity,
                        collapsed = disclosure.collapsed,
                        collapsible = disclosure.collapsible,
                        onToggleCollapsed = onToggle,
                    )
                }
            }
        }
    }

    private fun hasStateDescription(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private data class DisclosureConfiguration(
        val collapsed: Boolean = false,
        val collapsible: Boolean = true,
    )
}
