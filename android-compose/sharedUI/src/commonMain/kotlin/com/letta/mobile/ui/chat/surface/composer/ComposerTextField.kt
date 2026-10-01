package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_placeholder
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightSource
import com.letta.mobile.ui.mascot.MascotGazeSurface
import com.letta.mobile.ui.mascot.mascotGazeTarget
import com.letta.mobile.ui.theme.LettaDimens
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
)

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
    val flightSource = if (primary) rememberSendFlightSource() else Modifier
    // Where the user types: the agent's mascot glances here (letta-mobile-bglj6.1).
    val gaze = if (primary) Modifier.mascotGazeTarget(MascotGazeSurface.INPUT) else Modifier
    val handoff = LocalComposerFocusHandoff.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(primary, handoff) {
        if (primary && handoff?.focused == true) focusRequester.requestFocus()
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
            .then(flightSource)
            .then(gaze)
            .focusRequester(focusRequester)
            // Only the primary field speaks for the page: the outgoing one losing focus to it is not "unfocused".
            .onFocusChanged { if (primary && handoff != null && handoff.focused != it.isFocused) handoff.focused = it.isFocused }
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
                        onSend = model.actions::send,
                    ),
                )
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = style.singleLine,
        maxLines = style.maxLines,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = model.composer.placeholder ?: stringResource(Res.string.chat_surface_placeholder),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}
