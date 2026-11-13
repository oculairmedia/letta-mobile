package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MicOff
import com.composables.icons.lucide.Volume2
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceUserAction
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.components.LettaInlineNotice
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
import com.letta.mobile.sharedui.resources.voice_mode_interrupt
import com.letta.mobile.sharedui.resources.voice_mode_stop
import com.letta.mobile.sharedui.resources.voice_status_hearing
import com.letta.mobile.sharedui.resources.voice_status_listening
import com.letta.mobile.sharedui.resources.voice_status_permission
import com.letta.mobile.sharedui.resources.voice_status_setup
import com.letta.mobile.sharedui.resources.voice_status_speaking
import com.letta.mobile.sharedui.resources.voice_status_speaking_press
import com.letta.mobile.sharedui.resources.voice_status_thinking
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The chat bar's voice mode button, [size] across with a [iconSize] glyph: off it reads "Start
 * voice conversation"; on (listening, hearing, waiting, speaking) it sits on the primary container,
 * reads "Stop voice conversation" ("Stop speaking and listen" while the reply is spoken, since a
 * press then only interrupts it) and says which of those it is; after a failure it shows a crossed
 * microphone. A press goes to [VoiceModeHandle.press].
 */
@Composable
internal fun VoiceModeButton(handle: VoiceModeHandle, size: Dp, iconSize: Dp, modifier: Modifier = Modifier) {
    val state by handle.state.collectAsState()
    val look = buttonLook(state)
    val description = stringResource(buttonDescription(state))
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
 * What voice mode is doing, as the prompt field's placeholder while voice mode is on and the field is
 * empty ("Listening…", "Hearing you…", "Thinking…", "Speaking… — press to interrupt"); null while it
 * is off or waits on something (the notice above the card says what), so the field keeps its own
 * placeholder. The prompt's outline carries the state when the person has typed.
 */
@Composable
internal fun voicePlaceholder(): String? {
    val handle = LocalVoiceMode.current ?: return null
    val state by handle.state.collectAsState()
    val byVoice by handle.controller.bargeInByVoice.collectAsState()
    val text = when (state) {
        VoiceModeState.Listening, is VoiceModeState.TurnFailed -> Res.string.voice_status_listening
        VoiceModeState.Hearing -> Res.string.voice_status_hearing
        VoiceModeState.WaitingForReply -> Res.string.voice_status_thinking
        VoiceModeState.Speaking -> speakingHint(byVoice)
        else -> null
    } ?: return null
    return stringResource(text)
}

/**
 * Voice mode's notice in the composer's notice slot above the prompt card, when something needs the
 * person: the failure or the missing setup, permission or conversation, with the action that helps
 * (Retry, Set up) and, where voice mode has stopped, a dismiss that turns it off. The page's own
 * inline notice ([LettaInlineNotice]); nothing while voice mode is off or simply running.
 */
@Composable
internal fun ComposerVoiceNotice() {
    val handle = LocalVoiceMode.current ?: return
    val state by handle.state.collectAsState()
    val notice = voiceNotice(state) ?: return
    val kind = notice.action
    LettaInlineNotice(
        message = stringResource(notice.text),
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .testTag(VoiceModeTags.STRIP)
            .semantics { liveRegion = LiveRegionMode.Polite },
        actionLabel = kind?.let { stringResource(it.label) }.orEmpty(),
        onAction = kind?.let { action -> { act(action, handle) } },
        actionTag = VoiceModeTags.STRIP_ACTION,
        dismissLabel = stringResource(Res.string.voice_action_dismiss),
        onDismiss = if (notice.dismissible) handle.controller::stop else null,
        dismissTag = VoiceModeTags.STRIP_CLOSE,
    )
}

/** Does what the notice's [action] offers. */
private fun act(action: NoticeAction, handle: VoiceModeHandle) {
    when (action) {
        NoticeAction.RETRY -> handle.controller.retry()
        NoticeAction.PRESS_AGAIN -> handle.press()
        NoticeAction.SETUP -> handle.setupOpen = true
    }
}

/** What the notice offers. */
private enum class NoticeAction(val label: StringResource) {
    RETRY(Res.string.voice_action_retry),
    PRESS_AGAIN(Res.string.voice_action_retry),
    SETUP(Res.string.voice_action_setup),
}

@Immutable
private class VoiceNotice(val text: StringResource, val action: NoticeAction?, val dismissible: Boolean)

/** The notice for [state], or null where there is nothing to act on. */
private fun voiceNotice(state: VoiceModeState): VoiceNotice? = when (state) {
    is VoiceModeState.TurnFailed -> VoiceNotice(voiceErrorText(state.code), turnFailureAction(state), dismissible = false)
    is VoiceModeState.Error -> VoiceNotice(voiceErrorText(state.code), errorAction(state.code), dismissible = true)
    is VoiceModeState.NeedsSetup -> VoiceNotice(Res.string.voice_status_setup, NoticeAction.SETUP, dismissible = true)
    VoiceModeState.NeedsMicPermission -> VoiceNotice(Res.string.voice_status_permission, NoticeAction.PRESS_AGAIN, dismissible = true)
    VoiceModeState.NeedsConversation -> VoiceNotice(Res.string.voice_error_no_conversation, null, dismissible = true)
    else -> null
}

/** Voice mode listens on after a failed utterance: Retry sends it again; pressing the button would stop voice mode. */
private fun turnFailureAction(state: VoiceModeState.TurnFailed): NoticeAction? = when {
    state.retryable -> NoticeAction.RETRY
    state.code.action == VoiceUserAction.OPEN_SETTINGS -> NoticeAction.SETUP
    else -> null
}

private fun errorAction(code: VoiceErrorCode): NoticeAction = when (code.action) {
    VoiceUserAction.RETRY -> NoticeAction.RETRY
    VoiceUserAction.OPEN_SETTINGS -> NoticeAction.SETUP
    VoiceUserAction.NONE -> NoticeAction.PRESS_AGAIN
}

/** The speaking hint: talking interrupts only where the microphone path allows it; elsewhere a press does. */
private fun speakingHint(byVoice: Boolean): StringResource =
    if (byVoice) Res.string.voice_status_speaking else Res.string.voice_status_speaking_press

/** What a press of the button does now: stop the reply and listen while speaking, else start or stop. */
private fun buttonDescription(state: VoiceModeState): StringResource = when {
    state == VoiceModeState.Speaking -> Res.string.voice_mode_interrupt
    state.active -> Res.string.voice_mode_stop
    else -> Res.string.voice_mode_start
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
