package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.letta.mobile.sharedui.resources.composer_touch_placeholder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_placeholder
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightSource
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.mascot.MascotGazeSurface
import com.letta.mobile.ui.mascot.mascotGazeTarget
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: false for a prompt field that is on its way out. While the docked panel
 * grows into the page (or back) both layers' fields are briefly composed; only the one the
 * person is arriving at tells the send flight where a prompt takes off and the mascot where to
 * look, so neither is left pointing at the field that is about to go.
 */
internal val LocalComposerPrimary = compositionLocalOf { true }

/**
 * letta-mobile-bglj6.1: whether the page's prompt has keyboard focus. The docked bar and the full
 * card are different fields; when one takes over from the other mid-morph it takes the focus too,
 * so expanding (or collapsing) while typing carries on typing.
 */
@Stable
internal class ComposerFocusHandoff {
    var focused: Boolean by mutableStateOf(false)
}

/** The page's handoff; null outside a chat page. */
internal val LocalComposerFocusHandoff = staticCompositionLocalOf<ComposerFocusHandoff?> { null }

/** How a prompt field is laid out: the full card's multi-line field, or the dock's one line. */
internal data class ComposerFieldStyle(
    val testTag: String,
    val maxHeight: Dp,
    val singleLine: Boolean,
    val maxLines: Int,
    /** The soft keyboard's action key sends (Touch); on a pointer host Enter does. */
    val imeSend: Boolean = false,
    /** The phone's placeholder copy ("Type a message…") when the owner gives none. */
    val touchPlaceholder: Boolean = false,
) {
    /** Touch's soft keyboard shows a Send action key; elsewhere the defaults. */
    fun keyboardOptions(): KeyboardOptions {
        return if (imeSend) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default
    }

    /** The placeholder copy when the owner gives none. */
    fun placeholderRes(): StringResource {
        return if (touchPlaceholder) Res.string.composer_touch_placeholder else Res.string.chat_surface_placeholder
    }
}

/**
 * The prompt field. Lifted from desktop's ComposerTextField: it keeps its own
 * [TextFieldValue] (so an IME composition survives the owner echoing the same text back) and
 * adopts the owner's text only when it actually changes ([reconcileComposerFieldValue]).
 * Enter / Ctrl+Enter send, Shift+Enter is a newline, and a matched `/` action command runs.
 */
@Composable
internal fun ComposerTextField(
    model: ComposerModel,
    style: ComposerFieldStyle,
    modifier: Modifier = Modifier,
) {
    val text = model.composer.text
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, selection = TextRange(text.length))) }
    LaunchedEffect(text) { fieldValue = reconcileComposerFieldValue(fieldValue, text) }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val primary = LocalComposerPrimary.current
    val handoff = LocalComposerFocusHandoff.current
    val focus = remember(primary, handoff) { FieldFocus(primary, handoff) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(primary, handoff) {
        if (focus.restores()) focusRequester.requestFocus()
    }
    // A keyboard send (IME action, Enter) launches the flight like the bar's button does.
    val haptics = LocalHaptics.current
    val sendFromKeyboard = {
        if (model.decisions.sendEnabled) {
            haptics.play(LettaHapticCue.SendLaunch)
            model.actions.send()
        }
        true
    }
    BasicTextField(
        value = fieldValue,
        onValueChange = { next ->
            fieldValue = next
            if (next.text != text) model.actions.updateComposerText(next.text)
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LettaDimens.Space.xl, max = style.maxHeight)
            .testTag(style.testTag)
            .then(primaryFieldTargets(primary))
            .focusRequester(focusRequester)
            // Only the primary field speaks for the page: the outgoing one losing focus to it is not "unfocused".
            .onFocusChanged { focus.report(it) }
            .onPreviewKeyEvent { event ->
                composerEnterKeyHandled(
                    ComposerEnterKeyParams(
                        eventKey = event.key,
                        eventType = event.type,
                        shiftPressed = event.isShiftPressed,
                        ctrlPressed = event.isCtrlPressed,
                        matchedCommands = model.decisions.autocomplete.matchedCommands,
                        canSend = model.decisions.sendEnabled,
                        onRunCommand = { chooseComposerCommand(model.composer, model.decisions.autocomplete, model.actions, it) },
                        onSend = { sendFromKeyboard() },
                    ),
                )
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = style.singleLine,
        maxLines = style.maxLines,
        keyboardOptions = style.keyboardOptions(),
        keyboardActions = KeyboardActions(onSend = { sendFromKeyboard() }),
        decorationBox = { inner ->
            // Centred in the field's height, so a one-line bar's text sits mid-bar, not at its top.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (fieldValue.text.isEmpty()) FieldPlaceholder(model, style, textStyle)
                inner()
            }
        },
    )
}

/**
 * Where a prompt takes off and where the mascot glances (letta-mobile-bglj6.1): only the primary
 * field reports them.
 */
@Composable
private fun primaryFieldTargets(primary: Boolean): Modifier {
    if (!primary) return Modifier
    return rememberSendFlightSource().then(Modifier.mascotGazeTarget(MascotGazeSurface.INPUT))
}

/** One field's part in the page's focus handoff: only the primary field reads or reports it. */
private class FieldFocus(private val primary: Boolean, private val handoff: ComposerFocusHandoff?) {
    /** The page's prompt had focus as this field took over, so this field takes it. */
    fun restores(): Boolean {
        if (!primary) return false
        return handoff?.focused == true
    }

    fun report(state: FocusState) {
        if (!primary || handoff == null) return
        if (handoff.focused != state.isFocused) handoff.focused = state.isFocused
    }
}

/** The owner's placeholder, else the platform's own copy. */
@Composable
private fun FieldPlaceholder(model: ComposerModel, style: ComposerFieldStyle, textStyle: TextStyle) {
    Text(
        text = model.composer.placeholder ?: stringResource(style.placeholderRes()),
        style = textStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
