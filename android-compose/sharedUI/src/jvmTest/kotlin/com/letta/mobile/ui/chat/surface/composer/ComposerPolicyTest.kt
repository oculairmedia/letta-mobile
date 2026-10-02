package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from desktop's DesktopChatInteractionUiTest (the pure composer policy cases). */
class ComposerPolicyTest {
    @Test
    fun enterShortcutsCommandsAndDisabledSendFollowPolicy() {
        var sends = 0
        val ran = mutableListOf<ChatComposerCommand>()
        fun handled(
            shift: Boolean = false,
            ctrl: Boolean = false,
            canSend: Boolean = true,
            commands: List<ChatComposerCommand> = emptyList(),
            key: Key = Key.Enter,
        ) = composerEnterKeyHandled(
            ComposerEnterKeyParams(
                eventKey = key,
                eventType = KeyEventType.KeyDown,
                shiftPressed = shift,
                ctrlPressed = ctrl,
                matchedCommands = commands,
                canSend = canSend,
                onRunCommand = { ran += it },
                onSend = { sends++ },
            ),
        )

        assertTrue(handled())
        assertTrue(handled(ctrl = true))
        assertTrue(handled(key = Key.NumPadEnter))
        assertFalse(handled(shift = true))
        assertFalse(handled(canSend = false))
        assertFalse(handled(key = Key.A))
        val action = ChatComposerCommand(id = "new", label = "new", fillsComposer = false)
        val fill = ChatComposerCommand(id = "/skill", label = "/skill", fillsComposer = true)
        assertTrue(handled(canSend = false, commands = listOf(fill, action)))
        // A filling command never hijacks Enter: the draft sends as typed.
        assertTrue(handled(commands = listOf(fill)))
        assertEquals(4, sends)
        assertEquals(listOf(action), ran)
    }

    @Test
    fun composerStateBridgePreservesImeCompositionUntilExternalTextActuallyChanges() {
        val composing = TextFieldValue(text = "é", selection = TextRange(1), composition = TextRange(0, 1))

        assertEquals(composing, reconcileComposerFieldValue(composing, "é"))
        assertEquals(
            TextFieldValue(text = "reset", selection = TextRange(5)),
            reconcileComposerFieldValue(composing, "reset"),
        )
    }

    @Test
    fun composerHintShowsOnlyWhileThereIsNothingToSend() {
        assertTrue(composerHintVisible(text = "", hasAttachments = false))
        assertTrue(composerHintVisible(text = "   ", hasAttachments = false), "whitespace is not a message")
        assertFalse(composerHintVisible(text = "hello", hasAttachments = false))
        assertFalse(composerHintVisible(text = "", hasAttachments = true))
    }

    @Test
    fun actionStopsOnlyWhenThereIsNothingToQueue() {
        val queueing = ChatComposerUiState(text = "next", canQueueWhileStreaming = true)
        assertEquals(ComposerAction.Send, composerAction(queueing, streaming = false, cancelling = false))
        assertEquals(ComposerAction.Send, composerAction(queueing, streaming = true, cancelling = false))
        assertEquals(ComposerAction.Stop, composerAction(queueing.copy(text = ""), streaming = true, cancelling = false))
        assertEquals(ComposerAction.Stop, composerAction(queueing, streaming = true, cancelling = true))
        assertEquals(
            ComposerAction.Stop,
            composerAction(queueing.copy(canQueueWhileStreaming = false), streaming = true, cancelling = false),
        )
    }

    @Test
    fun sendNeedsAPayloadAndAnOwnerThatTakesIt() {
        val image = MessageContentPart.Image(base64 = "AA==", mediaType = "image/png")
        assertFalse(composerSendEnabled(ChatComposerUiState(text = "  ", canSend = true), streaming = false))
        assertTrue(composerSendEnabled(ChatComposerUiState(attachments = persistentListOf(image), canSend = true), false))
        assertFalse(composerSendEnabled(ChatComposerUiState(text = "hi", canSend = false), streaming = false))
        assertTrue(
            composerSendEnabled(ChatComposerUiState(text = "hi", canQueueWhileStreaming = true), streaming = true),
        )
    }

    @Test
    fun autocompleteMatchesCommandsAndMentionsAndRewritesTheToken() {
        val commands = persistentListOf(
            ChatComposerCommand(id = "new", label = "new"),
            ChatComposerCommand(id = "/review", label = "/review", fillsComposer = true),
        )
        val slash = composerAutocompleteUi(ChatComposerUiState(text = "/re", commands = commands))
        assertEquals(listOf("/review"), slash.matchedCommands.map { it.id })
        assertEquals("/review ", draftAfterCommand("/re", slash.commandToken, slash.matchedCommands.single()))
        assertEquals("", draftAfterCommand("/ne", ComposerAutocomplete.activeToken("/ne"), commands.first()))

        val mention = Mentionable(id = "f", label = "main.kt", sublabel = null, kind = MentionKind.File)
        val at = composerAutocompleteUi(ChatComposerUiState(text = "see @ma", mentionables = persistentListOf(mention)))
        assertEquals(MentionKind.File, at.mentionGroups.single().first)
        assertEquals("see @main.kt ", draftAfterMention("see @ma", at.mentionToken!!, mention))
    }

    @Test
    fun dictationJoinsTheDraft() {
        assertEquals("hello", draftAfterDictation("", "hello"))
        assertEquals("draft hello", draftAfterDictation("draft", "hello"))
        assertEquals("draft hello", draftAfterDictation("draft ", "hello"))
        assertEquals("draft", draftAfterDictation("draft", "  "))
    }

    @Test
    fun decisionsReadTheRunFromTheTimelineState() {
        val composer = ChatComposerUiState(text = "", canSend = true)
        val idle = ComposerDecisions.of(composer, ChatUiState())
        assertEquals(ComposerAction.Send, idle.action)
        assertFalse(idle.sendEnabled)
        val running = ComposerDecisions.of(composer, ChatUiState(isStreaming = true, isCancelling = true))
        assertEquals(ComposerAction.Stop, running.action)
        assertTrue(running.stopping)
    }
}
