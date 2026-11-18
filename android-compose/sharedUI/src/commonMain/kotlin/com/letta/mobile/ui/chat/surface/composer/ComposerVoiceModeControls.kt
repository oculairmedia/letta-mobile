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
import androidx.compose.runtime.LaunchedEffect
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
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.components.LettaInlineNotice
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.chat.voice.voiceErrorText
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_action_dismiss
import com.letta.mobile.sharedui.resources.voice_action_notice_close
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
import com.letta.mobile.sharedui.resources.voice_notice_handover
import com.letta.mobile.sharedui.resources.voice_status_permission
import com.letta.mobile.sharedui.resources.voice_status_setup
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
 * Voice mode's place above the prompt: while it runs, the hand-over notice when there is one (the
 * voice card itself extends from the prompt bar, VoiceCardDock); otherwise its notice when
 * something needs the person.
 */
@Composable
internal fun ComposerVoiceSlot() {
    val handle = LocalVoiceMode.current ?: return
    val state by handle.state.collectAsState()
    if (state.active) HandoverNotice(handle) else ComposerVoiceNotice()
}

/**
 * The reply to the person's newest message, as far as the chat has it: what the voice card lights as
 * it is spoken. Null before anything answers that message (never the previous reply).
 */
internal fun latestReplyText(state: ChatUiState): String? {
    for (message in state.messages.asReversed()) {
        when {
            message.role == USER -> return null
            message.role == ASSISTANT && !message.isReasoning && message.content.isNotBlank() -> return message.content
        }
    }
    return null
}

private const val ASSISTANT = "assistant"
private const val USER = "user"

/**
 * Voice mode's notice in the composer's notice slot above the prompt card, when voice mode has stopped
 * on something that needs the person: the failure or the missing setup, permission or conversation,
 * with the action that helps (Retry, Set up) and a dismiss that turns it off. The page's own inline
 * notice ([LettaInlineNotice]); nothing while voice mode is off or running (the voice card shows then).
 */
@Composable
private fun ComposerVoiceNotice() {
    val handle = LocalVoiceMode.current ?: return
    val state by handle.state.collectAsState()
    val notice = voiceNotice(state) ?: return HandoverNotice(handle)
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

/**
 * Said once when listening moved from the device's recogniser to the speech server mid-session:
 * a polite inline notice the person closes (voice mode keeps going), never a dialog.
 */
@Composable
private fun HandoverNotice(handle: VoiceModeHandle) {
    val reason by handle.handover.collectAsState()
    // A session's end clears the reason: the next hand-over is told again.
    LaunchedEffect(reason) { if (reason == null) handle.handoverClosed = false }
    if (reason == null || handle.handoverClosed) return
    LettaInlineNotice(
        message = stringResource(Res.string.voice_notice_handover),
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .testTag(VoiceModeTags.HANDOVER)
            .semantics { liveRegion = LiveRegionMode.Polite },
        dismissLabel = stringResource(Res.string.voice_action_notice_close),
        onDismiss = { handle.handoverClosed = true },
        dismissTag = VoiceModeTags.HANDOVER_CLOSE,
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

/** The notice for [state], or null where there is nothing to act on (a failed utterance shows in the voice card). */
private fun voiceNotice(state: VoiceModeState): VoiceNotice? = when (state) {
    is VoiceModeState.Error -> VoiceNotice(voiceErrorText(state.code), errorAction(state.code), dismissible = true)
    is VoiceModeState.NeedsSetup -> VoiceNotice(Res.string.voice_status_setup, NoticeAction.SETUP, dismissible = true)
    VoiceModeState.NeedsMicPermission -> VoiceNotice(Res.string.voice_status_permission, NoticeAction.PRESS_AGAIN, dismissible = true)
    VoiceModeState.NeedsConversation -> VoiceNotice(Res.string.voice_error_no_conversation, null, dismissible = true)
    else -> null
}

private fun errorAction(code: VoiceErrorCode): NoticeAction = when (code.action) {
    VoiceUserAction.RETRY -> NoticeAction.RETRY
    VoiceUserAction.OPEN_SETTINGS -> NoticeAction.SETUP
    VoiceUserAction.NONE -> NoticeAction.PRESS_AGAIN
}

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
