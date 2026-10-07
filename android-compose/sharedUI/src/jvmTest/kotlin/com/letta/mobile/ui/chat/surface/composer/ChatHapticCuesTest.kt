@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.haptics.Haptics
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bglj6.1.17: the chat surface describes its interactions with haptic cues through
 * the [LocalHaptics] seam (a host provides the platform backend; the default is silent). These
 * tests compose the Touch composer with a recording [Haptics] and assert the cues the interactions
 * fire: a send launches the flight, stopping the run reads as a confirm, and the "+" action opens
 * with a context click.
 */
class ChatHapticCuesTest {
    @Composable
    private fun TouchPanel(inputs: ComposerInputs, haptics: Haptics) {
        MaterialTheme {
            CompositionLocalProvider(
                LocalChatPlatformStyle provides ChatPlatformStyle.Touch,
                LocalHaptics provides haptics,
            ) {
                ChatComposerPanel(inputs)
            }
        }
    }

    private fun ready(text: String = "ready") = ChatComposerUiState(
        text = text,
        canSend = true,
        model = ChatModelUiState(currentHandle = null, currentLabel = "Model"),
    )

    @Test
    fun tappingSendPlaysTheSendLaunchCue() = runComposeUiTest {
        val played = mutableListOf<LettaHapticCue>()
        val actions = RecordingChatActions()
        setContent { TouchPanel(ComposerInputs(composer = ready(), actions = actions), Haptics { played += it }) }

        onNodeWithTag(ComposerTestTags.SEND).performClick()
        runOnIdle {
            assertEquals(listOf(LettaHapticCue.SendLaunch), played)
            assertEquals(1, actions.count("send"))
        }
    }

    @Test
    fun tappingStopPlaysTheConfirmCue() = runComposeUiTest {
        val played = mutableListOf<LettaHapticCue>()
        val actions = RecordingChatActions()
        val streaming = ChatUiState(isStreaming = true)
        setContent {
            TouchPanel(ComposerInputs(composer = ready(text = ""), actions = actions, uiState = streaming), Haptics { played += it })
        }

        onNodeWithTag(ComposerTestTags.STOP).performClick()
        runOnIdle {
            assertEquals(listOf(LettaHapticCue.Confirm), played)
            assertEquals(1, actions.count("stopRun"))
        }
    }

    @Test
    fun tappingPlusPlaysTheContextClickCue() = runComposeUiTest {
        val played = mutableListOf<LettaHapticCue>()
        // The "+" runs its single item directly; with image attach off and a canvas to open, the
        // single item is a mode intent — a file picker would throw HeadlessException on CI.
        val inputs = ComposerInputs(
            composer = ready(),
            actions = RecordingChatActions(),
            capabilities = ChatSurfaceCapabilities(attachImages = false),
            host = ChatSurfaceHost(openCanvas = {}),
        )
        setContent { TouchPanel(inputs, Haptics { played += it }) }

        onNodeWithTag(ComposerTestTags.TOUCH_PLUS).performClick()
        runOnIdle { assertEquals(listOf(LettaHapticCue.ContextClick), played) }
    }
}