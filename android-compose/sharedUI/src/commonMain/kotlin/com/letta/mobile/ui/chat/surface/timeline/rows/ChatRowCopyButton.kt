package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copied
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** What a [CopyIconButton] copies and how it announces itself. */
@Immutable
internal data class CopyAction(
    val text: String,
    val contentDescription: String,
    /** Full strength at rest instead of the quieter idle alpha. */
    val emphasized: Boolean = true,
    /**
     * Gates the icon on the surrounding row's hover state. Hidden is alpha 0, never removed:
     * the button keeps its hit target and its semantics, so layout never shifts and
     * accessibility and tests still find it.
     */
    val visible: Boolean = true,
)

/**
 * letta-mobile-bglj6.1: lifted from desktop's CopyIconButton. Click copies to the clipboard
 * and the glyph flips to a success check for a moment. Once the button itself is hovered,
 * focused or mid-"copied" it stays visible regardless of [CopyAction.visible].
 */
@Composable
internal fun CopyIconButton(
    action: CopyAction,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    var copied by rememberCopiedFeedback()
    val engaged = focused || hovered || copied
    val alpha by animateFloatAsState(
        targetValue = copyAlpha(action, engaged),
        animationSpec = tween(durationMillis = COPY_FADE_MILLIS),
        label = "copyActionAlpha",
    )
    val description = if (copied) stringResource(Res.string.rows_copied) else action.contentDescription
    val copy = {
        clipboard.setText(AnnotatedString(action.text))
        copied = true
    }
    // Hidden, a pointer must not hit it: taps meant for the text beneath go through (touch has
    // no hover to reveal it). Focus or hover reveals and arms it.
    val armed = action.visible || engaged
    Box(
        modifier = modifier
            .sizeIn(minWidth = LettaDimens.Control.actionButton, minHeight = LettaDimens.Control.actionButton)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .border(LettaDimens.Stroke.hairline, copyBorderColor(focused), CircleShape)
            // Always a button to assistive technology: TalkBack on a phone, with no hover to
            // reveal it, copies from here whether or not it shows.
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick { copy(); true }
            }
            // The one focus stop (a clickable would add a second): the keyboard reaches it
            // hidden, which reveals it, and Enter or Space copies.
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                isCopyActivation(event).also { if (it) copy() }
            }
            .indication(interactionSource, LocalIndication.current)
            .then(if (armed) Modifier.copyTap(interactionSource, copy) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        CopyGlyph(copied, tint)
    }
}

/** The copy glyph, or the success check for a moment after a copy. */
@Composable
private fun CopyGlyph(copied: Boolean, tint: Color) {
    Icon(
        imageVector = if (copied) LettaIcons.Check else LettaIcons.Copy,
        contentDescription = null,
        tint = if (copied) successTint() else tint,
        modifier = Modifier.size(LettaDimens.Control.icon),
    )
}

/** The "copied" flag, which falls back by itself once the feedback has shown. */
@Composable
private fun rememberCopiedFeedback(): MutableState<Boolean> {
    val copied = remember { mutableStateOf(false) }
    LaunchedEffect(copied.value) {
        if (copied.value) {
            delay(ChatRowDimens.copiedFeedbackMillis)
            copied.value = false
        }
    }
    return copied
}

@Composable
private fun copyBorderColor(focused: Boolean): Color {
    return if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
}

/** Enter or Space, on release. */
private fun isCopyActivation(event: KeyEvent): Boolean {
    return event.type == KeyEventType.KeyUp && event.key in ACTIVATION_KEYS
}

/** A tap copies; the press shows through [interactionSource] like a clickable's would. */
private fun Modifier.copyTap(interactionSource: MutableInteractionSource, onTap: () -> Unit): Modifier {
    return pointerInput(interactionSource) {
        detectTapGestures(
            onPress = { offset ->
                val press = PressInteraction.Press(offset)
                interactionSource.emit(press)
                interactionSource.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            },
            onTap = { onTap() },
        )
    }
}

private val ACTIVATION_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)

/** Shown when [CopyAction.visible] or the button itself is engaged (hovered, focused, just copied). */
private fun copyAlpha(action: CopyAction, engaged: Boolean): Float {
    return when {
        !(action.visible || engaged) -> 0f
        action.emphasized || engaged -> 1f
        else -> ChatRowAlpha.copyIdle
    }
}

@Composable
internal fun successTint(): Color {
    return MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
}

private const val COPY_FADE_MILLIS = 120
