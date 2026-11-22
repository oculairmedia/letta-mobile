@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.components.field.LettaFieldState
import com.letta.mobile.ui.components.field.LettaFieldStateKey
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import kotlin.test.Test

/**
 * The prompt card and the docked bar read as a text input: a quiet border at rest, a firmer one under
 * the pointer, the primary colour while the prompt (or a control in the card) holds focus.
 */
class ComposerFieldBorderUiTest {
    private val composer = ChatComposerUiState(
        text = "",
        model = ChatModelUiState(currentHandle = null, currentLabel = "Model"),
    )

    private fun ComposeUiTest.showPanel(mode: ChatSurfaceMode = ChatSurfaceMode.FullScreen) = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost(input = LettaInput.Pointer)) {
                Box(Modifier.width(800.dp)) {
                    ChatComposerPanel(ComposerInputs(composer = composer, actions = RecordingChatActions(), mode = mode))
                }
            }
        }
    }

    private fun ComposeUiTest.waitForLook(tag: String, look: LettaFieldState) = waitUntil(timeoutMillis = WAIT_MILLIS) {
        onAllNodes(SemanticsMatcher.expectValue(LettaFieldStateKey, look) and hasTestTag(tag)).fetchSemanticsNodes().size == 1
    }

    @Test
    fun theCardRestsThenFollowsThePointer() = runComposeUiTest {
        showPanel()
        waitForLook(ComposerTestTags.CARD, LettaFieldState.Rest)

        onNodeWithTag(ComposerTestTags.CARD).performMouseInput { moveTo(center) }
        waitForLook(ComposerTestTags.CARD, LettaFieldState.Hover)

        onNodeWithTag(ComposerTestTags.CARD).performMouseInput { exit() }
        waitForLook(ComposerTestTags.CARD, LettaFieldState.Rest)
    }

    @Test
    fun focusInThePromptLightsTheCardAndOutlastsThePointer() = runComposeUiTest {
        showPanel()
        onNodeWithTag(ComposerTestTags.INPUT).requestFocus()
        waitForLook(ComposerTestTags.CARD, LettaFieldState.Focus)

        // Focus wins: the pointer passing over a focused card keeps the focus look.
        onNodeWithTag(ComposerTestTags.CARD).performMouseInput { moveTo(center) }
        waitForLook(ComposerTestTags.CARD, LettaFieldState.Focus)
    }

    @Test
    fun theDockedBarFollowsFocusToo() = runComposeUiTest {
        showPanel(mode = ChatSurfaceMode.Docked)
        waitForLook(ComposerTestTags.DOCKED_BAR, LettaFieldState.Rest)

        onNodeWithTag(ComposerTestTags.DOCKED_INPUT).requestFocus()
        waitForLook(ComposerTestTags.DOCKED_BAR, LettaFieldState.Focus)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
