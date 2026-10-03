package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.letta.mobile.data.composer.ActiveToken
import com.letta.mobile.data.composer.AutocompleteTrigger
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.MentionCatalog
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState

/**
 * letta-mobile-bglj6.1: the composer's pure decisions (what the action button does, when
 * Enter sends, what the autocomplete offers), kept out of composables so they are tested
 * without a UI and read the same on Android and desktop.
 */

/** Most `/` suggestions shown at once. */
internal const val MaxCommandSuggestions = 8

/** What the composer's one action button does right now. */
internal enum class ComposerAction { Send, Stop }

/** The composer's derived state for one frame: computed once, read by every part. */
@Immutable
internal data class ComposerDecisions(
    /** The action button and Enter will send (or queue) the draft. */
    val sendEnabled: Boolean,
    val action: ComposerAction,
    /** A stop was requested and the terminal frame has not landed: the button reads "stopping". */
    val stopping: Boolean,
    val autocomplete: ComposerAutocompleteUi,
) {
    companion object {
        fun of(composer: ChatComposerUiState, uiState: ChatUiState): ComposerDecisions {
            val run = ComposerRun.of(uiState)
            return ComposerDecisions(
                sendEnabled = composerSendEnabled(composer, run),
                action = composerAction(composer, run),
                stopping = run.cancelling,
                autocomplete = composerAutocompleteUi(composer),
            )
        }
    }
}

/** Where the conversation's run stands, as the composer reads it. */
@Immutable
internal data class ComposerRun(
    /** A run is streaming. */
    val streaming: Boolean,
    /** A stop was requested and the terminal frame has not landed. */
    val cancelling: Boolean,
) {
    companion object {
        fun of(uiState: ChatUiState): ComposerRun {
            return ComposerRun(streaming = uiState.isStreaming, cancelling = uiState.isCancellingRun)
        }
    }
}

/**
 * A draft can be sent when there is something in it and the owner takes it now, or, during a
 * run, when the owner queues follow-ups behind the run.
 */
internal fun composerSendEnabled(composer: ChatComposerUiState, run: ComposerRun): Boolean {
    if (!composer.hasPayload) return false
    return composer.canSend || (run.streaming && composer.canQueueWhileStreaming)
}

/**
 * The action button stops the run only when there is nothing to queue: with a draft in the
 * field during a run it sends (queues) instead, and Stop returns once the field is empty.
 * A pending stop keeps the Stop button so a second press can force-clear.
 */
internal fun composerAction(composer: ChatComposerUiState, run: ComposerRun): ComposerAction {
    val stops = run.streaming && (run.cancelling || !composer.canQueueWhileStreaming || !composer.hasPayload)
    return if (stops) ComposerAction.Stop else ComposerAction.Send
}

/**
 * Whether the keyboard-affordance strip under the composer is showing: discovery copy for an
 * empty composer that fades as soon as there is something to send.
 */
internal fun composerHintVisible(composer: ChatComposerUiState): Boolean {
    return composer.text.isBlank() && composer.attachments.isEmpty()
}

@Immutable
internal data class ComposerAutocompleteUi(
    val commandToken: ActiveToken? = null,
    val matchedCommands: List<ChatComposerCommand> = emptyList(),
    val mentionToken: ActiveToken? = null,
    val mentionGroups: List<Pair<MentionKind, List<Mentionable>>> = emptyList(),
)

/** The `/` and `@` suggestions for the draft's active token (at its end, where typing happens). */
internal fun composerAutocompleteUi(composer: ChatComposerUiState): ComposerAutocompleteUi {
    val token = ComposerAutocomplete.activeToken(composer.text) ?: return ComposerAutocompleteUi()
    return when (token.trigger) {
        AutocompleteTrigger.Command -> ComposerAutocompleteUi(
            commandToken = token,
            matchedCommands = composer.commands
                .filter { it.label.contains(token.query, ignoreCase = true) }
                .take(MaxCommandSuggestions),
        )
        AutocompleteTrigger.Mention -> ComposerAutocompleteUi(
            mentionToken = token,
            mentionGroups = if (composer.mentionables.isEmpty()) {
                emptyList()
            } else {
                MentionCatalog.grouped(composer.mentionables, token.query)
            },
        )
    }
}

/**
 * The draft after choosing [command] for [token]: a [ChatComposerCommand.fillsComposer]
 * command replaces the token with its text for the user to finish; an action command clears
 * the token (it runs instead of being sent).
 */
internal fun draftAfterCommand(text: String, token: ActiveToken?, command: ChatComposerCommand): String {
    val replacement = if (command.fillsComposer) "${slashed(command.id)} " else ""
    return if (token == null) replacement else ComposerAutocomplete.replaceToken(text, token, replacement)
}

/** Server slash commands carry their `/`; app commands do not. Either way it is shown once. */
internal fun slashed(value: String): String {
    return if (value.startsWith("/")) value else "/$value"
}

/** The draft after choosing [mention] for the `@` [token]. */
internal fun draftAfterMention(text: String, token: ActiveToken, mention: Mentionable): String {
    return ComposerAutocomplete.replaceToken(text, token, "@${mention.insertText} ")
}

/** Dictated text joins the draft after a space, or becomes the draft when it is empty. */
internal fun draftAfterDictation(draft: String, dictated: String): String {
    return when {
        dictated.isBlank() -> draft
        draft.isBlank() -> dictated
        draft.last().isWhitespace() -> draft + dictated
        else -> "$draft $dictated"
    }
}

/**
 * Keeps the field's own value (selection, IME composition) while the owner's text agrees with
 * it; adopts the owner's text with the caret at the end only when it actually changed (a reset
 * after send, a command fill, dictation).
 */
internal fun reconcileComposerFieldValue(current: TextFieldValue, externalText: String): TextFieldValue {
    if (current.text == externalText) return current
    return TextFieldValue(text = externalText, selection = TextRange(externalText.length))
}

/** One key event at the prompt, with what Enter would do. */
internal data class ComposerEnterKeyParams(
    val eventKey: Key,
    val eventType: KeyEventType,
    val shiftPressed: Boolean,
    val ctrlPressed: Boolean,
    val matchedCommands: List<ChatComposerCommand>,
    val canSend: Boolean,
    /** Runs an action command matched by the typed `/x` (and clears its token). */
    val onRunCommand: (ChatComposerCommand) -> Unit,
    val onSend: () -> Unit,
)

/**
 * Enter / NumPadEnter (and Ctrl+Enter) send; Shift+Enter is a newline. An action command
 * matched by the typed `/x` runs on Enter instead of sending. Returns whether the event was
 * consumed; an unconsumed Enter falls through to the field as a newline.
 */
internal fun composerEnterKeyHandled(params: ComposerEnterKeyParams): Boolean {
    val isEnter = params.eventType == KeyEventType.KeyDown &&
        (params.eventKey == Key.Enter || params.eventKey == Key.NumPadEnter) &&
        !params.shiftPressed
    if (!isEnter) return false
    val actionCommand = params.matchedCommands.firstOrNull { !it.fillsComposer }
    return when {
        actionCommand != null -> {
            params.onRunCommand(actionCommand)
            true
        }
        params.canSend -> {
            params.onSend()
            true
        }
        else -> false
    }
}
