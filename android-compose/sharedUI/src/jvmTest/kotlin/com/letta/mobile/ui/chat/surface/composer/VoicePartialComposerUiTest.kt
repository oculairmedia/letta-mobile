@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceResponder
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Engines that never run: the handle's state and words are pinned by the test. */
private object QuietEngines : VoiceEar, VoiceMouth, VoiceResponder {
    override fun listen(): Flow<VoiceEarEvent> = emptyFlow()

    override fun commitTurn() = Unit

    override val events: Flow<VoiceMouthEvent> = emptyFlow()

    override fun speak(segment: SpeechSegment) = Unit

    override fun flush() = Unit

    override suspend fun stop() = Unit

    override fun respond(utterance: VoiceUtterance): VoiceReply = error("no turn is taken in these tests")
}

/**
 * The person's words so far, live in the voice card docked above the prompt while voice mode hears
 * them (its header saying "Hearing you"), kept in view however long they get (the card holds at most a
 * few lines and scrolls), never resizing the prompt or moving its controls and never typed into the
 * draft. At the phone, tablet and desktop widths, on touch and with a pointer.
 */
class VoicePartialComposerUiTest {
    private val empty = ChatComposerUiState(
        text = "",
        permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
    )
    private val words = MutableStateFlow<VoiceText?>(null)
    private val actions = RecordingChatActions()

    private fun ComposeUiTest.showComposer(width: Dp, style: LettaInput) = setContent {
        val scope = rememberCoroutineScope()
        val handle = remember {
            val store = VoiceSetupStore(InMemorySecureSettingsStore(), null)
            val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(QuietEngines, QuietEngines, QuietEngines)) {} }
            val controller = VoiceModeController(
                VoiceChatSessions { null },
                store,
                engines,
                MicrophoneAccess.Granted,
                VoiceLoopRuntime(scope, Dispatchers.Default),
            )
            VoiceModeHandle(controller, state = MutableStateFlow(VoiceModeState.Hearing), partialTranscript = words)
        }
        MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost(input = style), LocalVoiceMode provides handle) {
                Box(Modifier.width(width).height(PageHeight), contentAlignment = Alignment.BottomCenter) {
                    ChatComposerPanel(ComposerInputs(composer = empty, actions = actions))
                }
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.inCard(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag(VoiceModeTags.CARD)), useUnmergedTree = true)

    private fun showsWordsInPlace(width: Dp, style: LettaInput, bar: String) = runComposeUiTest {
        showComposer(width, style)
        waitForIdle()
        val where = "at $width ($style)"
        val barBefore = bounds(bar)
        val slotBefore = bounds(ComposerTestTags.TRAILING_ACTIONS)
        val fieldBefore = bounds(ComposerTestTags.INPUT)
        onNodeWithTag(VoiceModeTags.CARD_STATUS, useUnmergedTree = true).assert(hasText("Hearing you"))

        words.value = VoiceText("what time")
        waitForIdle()
        inCard("what time").assertExists()
        val shortCard = bounds(VoiceModeTags.CARD)
        words.value = VoiceText(LONG_WORDS)
        waitForIdle()

        val card = bounds(VoiceModeTags.CARD)
        assertEquals(barBefore, bounds(bar), "the prompt keeps its size $where")
        assertEquals(slotBefore, bounds(ComposerTestTags.TRAILING_ACTIONS), "the trailing controls stay put $where")
        assertEquals(fieldBefore, bounds(ComposerTestTags.INPUT), "the field keeps its size $where")
        assertTrue(card.top < barBefore.top && card.bottom > barBefore.top, "the card extends from the prompt, tucked under it $where")
        assertTrue(card.right <= width + 1.dp && card.left >= (-1).dp, "the card stays inside the page $where")
        assertTrue(card.height < shortCard.height * MAX_GROWTH, "long words scroll in the card rather than growing it without end $where")
        inCard(LONG_WORDS).assertExists()
        assertTrue(actions.texts.isEmpty(), "the words never become draft text $where")

        words.value = null
        waitForIdle()
        inCard(LONG_WORDS).assertDoesNotExist()
        assertEquals(barBefore, bounds(bar), "the prompt is unchanged once the words clear $where")
    }

    @Test
    fun thePhoneBarShowsTheWordsInPlaceAt360() = showsWordsInPlace(360.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR)

    @Test
    fun theTouchBarShowsTheWordsInPlaceAt700() = showsWordsInPlace(700.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR)

    @Test
    fun theTouchBarShowsTheWordsInPlaceAt760() = showsWordsInPlace(760.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR)

    @Test
    fun theTouchBarShowsTheWordsInPlaceAt1200() = showsWordsInPlace(1200.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR)

    @Test
    fun theCardShowsTheWordsInPlaceAt360() = showsWordsInPlace(360.dp, LettaInput.Pointer, ComposerTestTags.CARD)

    @Test
    fun theCardShowsTheWordsInPlaceAt700() = showsWordsInPlace(700.dp, LettaInput.Pointer, ComposerTestTags.CARD)

    @Test
    fun theCardShowsTheWordsInPlaceAt760() = showsWordsInPlace(760.dp, LettaInput.Pointer, ComposerTestTags.CARD)

    @Test
    fun theCardShowsTheWordsInPlaceAt1200() = showsWordsInPlace(1200.dp, LettaInput.Pointer, ComposerTestTags.CARD)

    private companion object {
        val PageHeight = 900.dp
        val LONG_WORDS = "so what I was thinking is that we could " + "keep talking for a very long time ".repeat(12)

        /** The card may grow to its few lines of words, but no further. */
        const val MAX_GROWTH = 4
    }
}
