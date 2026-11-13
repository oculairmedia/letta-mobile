package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MicOff
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.X
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceUserAction
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.chat.voice.voiceErrorText
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_action_dismiss
import com.letta.mobile.sharedui.resources.voice_action_retry
import com.letta.mobile.sharedui.resources.voice_action_setup
import com.letta.mobile.sharedui.resources.voice_error_no_conversation
import com.letta.mobile.sharedui.resources.voice_mode_start
import com.letta.mobile.sharedui.resources.voice_mode_state_error
import com.letta.mobile.sharedui.resources.voice_mode_state_hearing
import com.letta.mobile.sharedui.resources.voice_mode_state_listening
import com.letta.mobile.sharedui.resources.voice_mode_state_off
import com.letta.mobile.sharedui.resources.voice_mode_state_permission
import com.letta.mobile.sharedui.resources.voice_mode_state_setup
import com.letta.mobile.sharedui.resources.voice_mode_state_speaking
import com.letta.mobile.sharedui.resources.voice_mode_state_thinking
import com.letta.mobile.sharedui.resources.voice_mode_stop
import com.letta.mobile.sharedui.resources.voice_status_hearing
import com.letta.mobile.sharedui.resources.voice_status_listening
import com.letta.mobile.sharedui.resources.voice_status_permission
import com.letta.mobile.sharedui.resources.voice_status_setup
import com.letta.mobile.sharedui.resources.voice_status_speaking
import com.letta.mobile.sharedui.resources.voice_status_thinking
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The chat bar's voice mode button, [size] across with a [iconSize] glyph: off it reads "Start
 * voice conversation"; on (listening, hearing, waiting, speaking) it sits on the primary container,
 * reads "Stop voice conversation" and says which of those it is; after a failure it shows a crossed
 * microphone. A press goes to [VoiceModeHandle.press].
 */
@Composable
internal fun VoiceModeButton(handle: VoiceModeHandle, size: Dp, iconSize: Dp, modifier: Modifier = Modifier) {
    val state by handle.controller.state.collectAsState()
    val look = buttonLook(state)
    val description = stringResource(if (state.active) Res.string.voice_mode_stop else Res.string.voice_mode_start)
    val stateText = stringResource(look.state)
    val container by animateColorAsState(look.container, colorSpec(), label = "voiceModeContainer")
    val content by animateColorAsState(look.content, colorSpec(), label = "voiceModeContent")
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .toggleable(value = state.active, role = Role.Button, onValueChange = { handle.press() })
            .semantics {
                contentDescription = description
                stateDescription = stateText
            }
            .testTag(VoiceModeTags.BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        Icon(look.icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = content)
    }
}

/**
 * One compact line directly above the chat bar while voice mode is not off: what it is doing
 * ("Listening…", "Speaking… — talk to interrupt"), or why it stopped with the action that helps
 * (Retry, Set up), and a close control that stops voice mode. The line is a polite live region.
 */
@Composable
internal fun VoiceStatusStrip(handle: VoiceModeHandle, modifier: Modifier = Modifier) {
    val state by handle.controller.state.collectAsState()
    if (state == VoiceModeState.Off) return
    val line = statusLine(state)
    Surface(
        modifier = modifier.fillMaxWidth().testTag(VoiceModeTags.STRIP),
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = LettaDimens.Space.md, end = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(line.text),
                style = MaterialTheme.typography.bodySmall,
                color = if (line.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            line.action?.let { action -> StripAction(action, handle) }
            IconButton(
                onClick = handle.controller::stop,
                modifier = Modifier.size(LettaDimens.Control.iconButton).testTag(VoiceModeTags.STRIP_CLOSE),
            ) {
                val dismiss = stringResource(Res.string.voice_action_dismiss)
                Icon(Lucide.X, contentDescription = dismiss, modifier = Modifier.size(LettaDimens.Control.iconSm))
            }
        }
    }
}

/** What the strip offers next to its text. */
private enum class StripActionKind(val label: StringResource) {
    RETRY(Res.string.voice_action_retry),
    PRESS_AGAIN(Res.string.voice_action_retry),
    SETUP(Res.string.voice_action_setup),
}

@Composable
private fun StripAction(action: StripActionKind, handle: VoiceModeHandle) {
    TextButton(
        onClick = {
            when (action) {
                StripActionKind.RETRY -> handle.controller.retry()
                StripActionKind.PRESS_AGAIN -> handle.press()
                StripActionKind.SETUP -> handle.setupOpen = true
            }
        },
        contentPadding = PaddingValues(horizontal = LettaDimens.Space.sm),
        modifier = Modifier.heightIn(min = LettaDimens.Control.iconButton).testTag(VoiceModeTags.STRIP_ACTION),
    ) {
        Text(stringResource(action.label), style = MaterialTheme.typography.labelMedium)
    }
}

@Immutable
private class StatusLine(val text: StringResource, val action: StripActionKind? = null, val problem: Boolean = false)

private fun statusLine(state: VoiceModeState): StatusLine = when (state) {
    VoiceModeState.Listening -> StatusLine(Res.string.voice_status_listening)
    VoiceModeState.Hearing -> StatusLine(Res.string.voice_status_hearing)
    VoiceModeState.WaitingForReply -> StatusLine(Res.string.voice_status_thinking)
    VoiceModeState.Speaking -> StatusLine(Res.string.voice_status_speaking)
    is VoiceModeState.NeedsSetup -> StatusLine(Res.string.voice_status_setup, StripActionKind.SETUP, problem = true)
    VoiceModeState.NeedsMicPermission -> StatusLine(Res.string.voice_status_permission, StripActionKind.PRESS_AGAIN, problem = true)
    VoiceModeState.NeedsConversation -> StatusLine(Res.string.voice_error_no_conversation, problem = true)
    is VoiceModeState.Error -> StatusLine(voiceErrorText(state.code), errorAction(state.code), problem = true)
    is VoiceModeState.TurnFailed -> StatusLine(voiceErrorText(state.code), turnFailureAction(state), problem = true)
    VoiceModeState.Off -> StatusLine(Res.string.voice_mode_state_off)
}

/** Voice mode listens on after a failed utterance: Retry sends it again; pressing the button would stop voice mode. */
private fun turnFailureAction(state: VoiceModeState.TurnFailed): StripActionKind? = when {
    state.retryable -> StripActionKind.RETRY
    state.code.action == VoiceUserAction.OPEN_SETTINGS -> StripActionKind.SETUP
    else -> null
}

private fun errorAction(code: VoiceErrorCode): StripActionKind? = when (code.action) {
    VoiceUserAction.RETRY -> StripActionKind.RETRY
    VoiceUserAction.OPEN_SETTINGS -> StripActionKind.SETUP
    VoiceUserAction.NONE -> StripActionKind.PRESS_AGAIN
}

/** The button's glyph, colours and spoken state for one voice mode state. */
@Immutable
private class ButtonLook(val icon: ImageVector, val container: Color, val content: Color, val state: StringResource)

@Composable
private fun buttonLook(state: VoiceModeState): ButtonLook {
    val scheme = MaterialTheme.colorScheme
    val on = { icon: ImageVector, text: StringResource -> ButtonLook(icon, scheme.primaryContainer, scheme.onPrimaryContainer, text) }
    val off = { icon: ImageVector, tint: Color, text: StringResource -> ButtonLook(icon, Color.Transparent, tint, text) }
    return when (state) {
        VoiceModeState.Listening -> on(Lucide.AudioLines, Res.string.voice_mode_state_listening)
        VoiceModeState.Hearing -> on(Lucide.AudioLines, Res.string.voice_mode_state_hearing)
        VoiceModeState.WaitingForReply -> on(Lucide.AudioLines, Res.string.voice_mode_state_thinking)
        VoiceModeState.Speaking -> on(Lucide.Volume2, Res.string.voice_mode_state_speaking)
        is VoiceModeState.TurnFailed -> on(Lucide.AudioLines, Res.string.voice_mode_state_listening)
        VoiceModeState.NeedsMicPermission -> off(Lucide.MicOff, scheme.error, Res.string.voice_mode_state_permission)
        is VoiceModeState.Error -> off(Lucide.MicOff, scheme.error, Res.string.voice_mode_state_error)
        is VoiceModeState.NeedsSetup -> off(Lucide.AudioLines, scheme.onSurface, Res.string.voice_mode_state_setup)
        VoiceModeState.NeedsConversation, VoiceModeState.Off -> off(Lucide.AudioLines, scheme.onSurface, Res.string.voice_mode_state_off)
    }
}

/** Colour changes ease over the chips' duration, or snap under reduced motion. */
@Composable
private fun colorSpec(): AnimationSpec<Color> = if (LocalReducedMotion.current) snap() else tween(LettaMotionTokens.CHIP_MILLIS)
