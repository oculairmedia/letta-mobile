@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.modelcontrol.ModelPickerEffortTags
import com.letta.mobile.ui.modelcontrol.ModelPickerTags
import com.letta.mobile.ui.modelcontrol.RecordedPicks
import com.letta.mobile.ui.modelcontrol.pickerModel
import com.letta.mobile.ui.modelcontrol.testModelPicker
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The prompt bar's one model control (model and effort in one picker) and the permission mode (a
 * line under the pointer card, a row of the Touch bar's "+" sheet), at the phone (360), tablet
 * (700, 760) and desktop (1200) widths, for the pointer card and the Touch bar.
 */
class ComposerUnifiedModelControlUiTest {
    private val withEfforts = ChatModelOption(
        handle = "openai/a",
        label = "Model A",
        provider = "OpenAI",
        reasoningEfforts = persistentListOf("high", "low"),
    )
    private val withoutEfforts = ChatModelOption(handle = "anthropic/b", label = "Model B", provider = "Anthropic")

    private fun composer(current: ChatModelOption = withEfforts, effort: String? = "high") = ChatComposerUiState(
        text = "ready",
        canSend = true,
        model = ChatModelUiState(
            currentHandle = current.handle,
            currentLabel = current.label,
            currentEffort = effort,
            options = persistentListOf(withEfforts, withoutEfforts),
        ),
        permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.AcceptEdits),
    )

    /** One composer at [width] under [input], in a window wide and tall enough for the picker. */
    private class Scene(val width: Dp, val input: LettaInput, val composer: ChatComposerUiState) {
        val actions = RecordingChatActions()
        val picks = RecordedPicks()
        val picker = testModelPicker(
            models = listOf(pickerModel("openai/a", "Model A"), pickerModel("anthropic/b", "Model B")),
            picks = picks,
        )
    }

    private fun scene(
        width: Dp,
        input: LettaInput,
        composer: ChatComposerUiState = composer(),
        check: ComposeUiTest.(Scene) -> Unit,
    ) {
        val scene = Scene(width, input, composer)
        runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
            setContent {
                MaterialTheme {
                    ProvideLettaHost(if (input == LettaInput.Pointer) LettaHost.Pointer else LettaHost(input = input)) {
                        Box(Modifier.width(width)) {
                            ChatComposerPanel(
                                ComposerInputs(
                                    composer = scene.composer,
                                    actions = scene.actions,
                                    host = ChatSurfaceHost(modelPicker = scene.picker),
                                    platform = ChatSurfacePlatform(showKeyboardHints = input == LettaInput.Pointer),
                                ),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            check(scene)
        }
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun thePointerCardHasOneModelControlNamingTheEffortAndNoEffortControl() = WIDTHS.forEach { width ->
        scene(width, LettaInput.Pointer) {
            assertEquals(1, count(ComposerTestTags.MODEL_CHIP), "model controls at $width")
            assertEquals(0, count(OLD_EFFORT_CHIP), "a separate effort control at $width")
            val control = onNodeWithTag(ComposerTestTags.MODEL_CHIP)
            control.assertTextContains("Model A", substring = true).assertTextContains("· High", substring = true)
            val config = control.fetchSemanticsNode().config
            val label = config.getOrNull(SemanticsProperties.ContentDescription)
            assertEquals(listOf("Model and effort"), label, "control label at $width")
            assertEquals("Model A, High effort", config.getOrNull(SemanticsProperties.StateDescription), "control state at $width")
        }
    }

    @Test
    fun theTouchBarHasNoModelOrEffortControlAndOffersTheModeInItsPlusSheet() = WIDTHS.forEach { width ->
        scene(width, LettaInput.Touch) { scene ->
            assertEquals(0, count(ComposerTestTags.MODEL_CHIP), "model controls in the Touch bar at $width")
            assertEquals(0, count(OLD_EFFORT_CHIP), "a separate effort control at $width")
            assertEquals(0, count(ComposerTestTags.PERMISSION_MODE_LINE), "a mode line under the Touch bar at $width")
            // The bar is main's: full width, the same height, the row untouched.
            val bar = onNodeWithTag(ComposerTestTags.TOUCH_BAR).getUnclippedBoundsInRoot()
            assertEquals(0.dp, bar.left, "bar start at $width")
            assertEquals(width, bar.right - bar.left, "bar width at $width")
            assertEquals(MAIN_TOUCH_BAR_HEIGHT, bar.bottom - bar.top, "bar height at $width")
            val input = onNodeWithTag(ComposerTestTags.INPUT).getUnclippedBoundsInRoot()
            assertTrue(input.right - input.left >= width - TOUCH_ROW_CHROME, "field $input narrowed at $width")

            onNodeWithContentDescription("Add to message").performClick()
            onNodeWithText("Permission mode: Accept edits").performClick()
            onNodeWithTag(ComposerTestTags.PERMISSION_MODE_OPTION + AppServerPermissionMode.Strict.name).performClick()
            runOnIdle { assertEquals(listOf(AppServerPermissionMode.Strict), scene.actions.permissionModes, "mode pick at $width") }
        }
    }

    @Test
    fun choosingAModelAndAnEffortSendsWhatTheTwoControlsSent() = WIDTHS.forEach { width ->
        scene(width, LettaInput.Pointer) { scene ->
            onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
            onNodeWithTag(ModelPickerTags.ROW_PREFIX + "anthropic/b").performClick()
            runOnIdle {
                assertEquals(listOf("anthropic/b"), scene.picks.models, "model pick at $width")
                assertFalse(scene.picker.pickerOpen)
            }

            onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
            onNodeWithTag(ModelPickerEffortTags.GROUP).assertExists()
            val group = onNodeWithTag(ModelPickerEffortTags.GROUP).fetchSemanticsNode().config
            assertEquals(listOf("Effort for Model A"), group.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(true, choice("high").config.getOrNull(SemanticsProperties.Selected), "current effort at $width")
            onNodeWithTag(ModelPickerEffortTags.CHOICE_PREFIX + ModelPickerEffortTags.DEFAULT_CHOICE).performClick()
            runOnIdle { assertFalse(scene.picker.pickerOpen, "an effort pick closes the picker at $width") }

            onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
            onNodeWithTag(ModelPickerEffortTags.CHOICE_PREFIX + "low").performClick()
            val expected = listOf(
                "openai/a" to ReasoningEffortChoice.ProviderDefault,
                "openai/a" to ReasoningEffortChoice.Named("low"),
            )
            runOnIdle { assertEquals(expected, scene.actions.models, "effort picks at $width") }
        }
    }

    private fun ComposeUiTest.choice(effort: String) =
        onNodeWithTag(ModelPickerEffortTags.CHOICE_PREFIX + effort).fetchSemanticsNode()

    @Test
    fun theEffortChoicesFollowTheLadderWithDefaultFirst() = scene(360.dp, LettaInput.Pointer) {
        onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
        val order = listOf(ModelPickerEffortTags.DEFAULT_CHOICE, "low", "high")
            .map { onNodeWithTag(ModelPickerEffortTags.CHOICE_PREFIX + it).getUnclippedBoundsInRoot() }
        order.zipWithNext().forEach { (before, after) ->
            assertTrue(after.left > before.left || after.top > before.top, "choice $after is not after $before")
        }
    }

    @Test
    fun aModelWithoutEffortLevelsSaysSoInsteadOfOfferingChoices() = WIDTHS.forEach { width ->
        scene(width, LettaInput.Pointer, composer(current = withoutEfforts, effort = null)) {
            val control = onNodeWithTag(ComposerTestTags.MODEL_CHIP)
            control.assertTextContains("Model B", substring = true)
            val text = control.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()
            assertFalse("·" in text, "a model without efforts names one at $width: $text")
            assertEquals("Model B", control.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))

            control.performClick()
            onNodeWithTag(ModelPickerEffortTags.UNAVAILABLE).assertTextContains("Model B has no effort levels")
            assertEquals(0, count(ModelPickerEffortTags.GROUP), "effort choices for a model without them at $width")
        }
    }

    @Test
    fun theModeLineSitsUnderTheCardOpensThePickerAndLeavesTheFieldAndSendTheirRoom() = WIDTHS.forEach { width ->
        scene(width, LettaInput.Pointer) { scene ->
            val card = onNodeWithTag(ComposerTestTags.CARD).getUnclippedBoundsInRoot()
            val mode = onNodeWithTag(ComposerTestTags.PERMISSION_MODE_LINE).getUnclippedBoundsInRoot()
            assertTrue(mode.top >= card.bottom, "mode line $mode is not under the card $card at $width")
            assertTrue(mode.left >= card.left && mode.right <= card.right, "mode line $mode overflows $card at $width")
            assertTrue(mode.bottom - mode.top <= LettaDimens.Space.xl, "mode line $mode is more than one line at $width")
            val controls = onAllNodesWithTag(ComposerTestTags.PERMISSION_MODE_LINE).fetchSemanticsNodes()
            assertEquals(1, controls.size)
            val inCard = hasTestTag(ComposerTestTags.PERMISSION_MODE_LINE) and hasAnyAncestor(hasTestTag(ComposerTestTags.CARD))
            assertEquals(0, onAllNodes(inCard).fetchSemanticsNodes().size, "mode control inside the card at $width")

            val input = onNodeWithTag(ComposerTestTags.INPUT).getUnclippedBoundsInRoot()
            val send = onNodeWithTag(ComposerTestTags.SEND).getUnclippedBoundsInRoot()
            val before = card.right - card.left - FIELD_RESERVE_BEFORE - ROUNDING
            assertTrue(input.right - input.left >= before, "field $input narrowed in $card at $width")
            assertTrue(input.right <= send.left, "field $input runs under Send $send at $width")
            assertTrue(send.left >= card.left && send.right <= card.right, "Send $send overflows $card at $width")
            assertTrue(send.right - send.left >= LettaDimens.Control.actionButton, "Send $send shrank at $width")

            onNodeWithTag(ComposerTestTags.PERMISSION_MODE_LINE).performClick()
            onNodeWithTag(ComposerTestTags.PERMISSION_MODE_OPTION + AppServerPermissionMode.Strict.name).performClick()
            runOnIdle { assertEquals(listOf(AppServerPermissionMode.Strict), scene.actions.permissionModes, "mode pick at $width") }
        }
    }

    @Test
    fun aLongModeEllipsizesOnOneLineAtPhoneWidth() {
        val long = composer().copy(
            permissionMode = ChatPermissionModeUiState(
                selected = AppServerPermissionMode.Standard,
                unconfirmed = AppServerPermissionMode.AcceptEdits,
            ),
        )
        scene(360.dp, LettaInput.Pointer, long) {
            val mode = onNodeWithTag(ComposerTestTags.PERMISSION_MODE_LINE).getUnclippedBoundsInRoot()
            assertTrue(mode.bottom - mode.top <= LettaDimens.Space.xl, "mode line $mode wrapped at 360dp")
        }
    }

    private companion object {
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 900

        /** The removed effort control's tag: nothing may carry it now. */
        const val OLD_EFFORT_CHIP = "composer-effort"

        /**
         * What the card's field gave up before the model and effort controls merged: the card's
         * 12 dp insets on each side and the 42 dp it kept clear of Send. The field may only get wider.
         */
        val FIELD_RESERVE_BEFORE = 66.dp
        val ROUNDING = 0.5.dp

        /** The Touch bar's height on main (its rendered height before this change): the bar is unchanged. */
        val MAIN_TOUCH_BAR_HEIGHT = 104.dp

        /** The Touch row's chrome around its field: the bar's insets, the "+" and Send targets, gaps and field padding. */
        val TOUCH_ROW_CHROME = 160.dp
    }
}
