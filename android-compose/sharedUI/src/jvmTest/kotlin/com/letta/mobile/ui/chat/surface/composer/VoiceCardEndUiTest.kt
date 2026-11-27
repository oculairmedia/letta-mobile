@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.responder.VoiceConversationKey
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertTrue

/** An ear that opens and hears nothing. */
private class SilentEar : VoiceEar {
    override fun listen(): Flow<VoiceEarEvent> = flow {
        emit(VoiceEarEvent.ListeningStarted)
        awaitCancellation()
    }

    override fun commitTurn() = Unit
}

private class IdleSession : VoiceChatSession {
    override val state: StateFlow<VoiceChatState> = MutableStateFlow(VoiceChatState(conversation = VoiceConversationKey("c1")))

    override suspend fun send(text: VoiceText): SentUtterance = SentUtterance(ClientMessageId("m1"))

    override fun stopRun() = Unit
}

/**
 * The voice card's red End pill is one target: a press anywhere on it (its word as much as its cross)
 * ends voice mode, by touch and by mouse, on the real composer and the real controller. It keeps its
 * label and button role and reserves at least the platform's minimum touch target on touch.
 */
class VoiceCardEndUiTest {
    @Composable
    private fun rememberVoice(): VoiceModeHandle {
        val scope = rememberCoroutineScope()
        return remember {
            val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
            store.save(VoiceSetupDraft("http://127.0.0.1:8880", consent = true))
            val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(SilentEar(), CardQuietEngines, CardQuietEngines)) {} }
            val runtime = VoiceLoopRuntime(scope, Dispatchers.Default)
            VoiceModeHandle(VoiceModeController(VoiceChatSessions { IdleSession() }, store, engines, MicrophoneAccess.Granted, runtime))
        }
    }

    private fun ComposeUiTest.showComposer(width: Dp, input: LettaInput, onHandle: (VoiceModeHandle) -> Unit) = setContent {
        val handle = rememberVoice().also(onHandle)
        MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost(input = input), LocalVoiceMode provides handle) {
                Box(Modifier.width(width).height(PAGE_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                    ChatComposerPanel(
                        ComposerInputs(
                            composer = ChatComposerUiState(
                                text = "",
                                permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
                            ),
                            actions = RecordingChatActions(),
                        ),
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.startVoice(width: Dp, input: LettaInput): () -> VoiceModeHandle {
        var handle: VoiceModeHandle? = null
        showComposer(width, input) { handle = it }
        onNodeWithTag(VoiceModeTags.BUTTON).performClick()
        waitUntil(timeoutMillis = 5_000) { handle?.controller?.state?.value == VoiceModeState.Listening }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(VoiceModeTags.CARD_END)).fetchSemanticsNodes().isNotEmpty() }
        return { handle!! }
    }

    /** End's word, not its cross: the text node inside the pill. */
    private fun ComposeUiTest.endWord(): SemanticsNodeInteraction =
        onNode(hasText("End") and hasAnyAncestor(hasTestTag(VoiceModeTags.CARD_END)), useUnmergedTree = true)

    private fun ComposeUiTest.endsOnTheWord(width: Dp, input: LettaInput, press: SemanticsNodeInteraction.() -> Unit) {
        val handle = startVoice(width, input)
        onNodeWithTag(VoiceModeTags.CARD_END)
            .assertContentDescriptionEquals("End voice conversation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        endWord().press()
        waitUntil(timeoutMillis = 5_000) { handle().controller.state.value == VoiceModeState.Off }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(VoiceModeTags.CARD)).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag(VoiceModeTags.CARD).assertDoesNotExist()
    }

    @Test
    fun aTapOnEndsWordEndsVoiceModeOnTouch() = runComposeUiTest {
        endsOnTheWord(360.dp, LettaInput.Touch) { performTouchInput { click(center) } }
    }

    @Test
    fun aClickOnEndsWordEndsVoiceModeWithTheMouse() = runComposeUiTest {
        endsOnTheWord(1200.dp, LettaInput.Pointer) { performMouseInput { click(center) } }
    }

    @Test
    fun endReservesTheMinimumTouchTargetOnTouch() = runComposeUiTest {
        startVoice(360.dp, LettaInput.Touch)
        val end = onNodeWithTag(VoiceModeTags.CARD_END).getUnclippedBoundsInRoot()
        assertTrue(end.height >= LettaControlTokens.minTouchTarget, "End's target is ${end.height} tall")
    }

    private companion object {
        val PAGE_HEIGHT = 640.dp
    }
}
