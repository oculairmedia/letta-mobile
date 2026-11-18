package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_card_collapse
import com.letta.mobile.sharedui.resources.voice_card_elapsed
import com.letta.mobile.sharedui.resources.voice_card_end
import com.letta.mobile.sharedui.resources.voice_card_end_description
import com.letta.mobile.sharedui.resources.voice_card_expand
import com.letta.mobile.sharedui.resources.voice_card_mic_mute
import com.letta.mobile.sharedui.resources.voice_card_speaker_mute
import com.letta.mobile.sharedui.resources.voice_card_speed
import com.letta.mobile.sharedui.resources.voice_card_speed_value
import com.letta.mobile.sharedui.resources.voice_card_toggle_off
import com.letta.mobile.sharedui.resources.voice_card_toggle_on
import com.letta.mobile.sharedui.resources.voice_mode_interrupt
import com.letta.mobile.ui.chat.voice.VoiceCardFeed
import com.letta.mobile.ui.chat.voice.VoiceCardMuteLook
import com.letta.mobile.ui.chat.voice.VoiceCardSpeaker
import com.letta.mobile.ui.chat.voice.VoiceCardSpeed
import com.letta.mobile.ui.chat.voice.VoiceCardSwitch
import com.letta.mobile.ui.chat.voice.VoiceCardTurn
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.components.disclosure.LettaFoldButton
import com.letta.mobile.ui.components.disclosure.LettaMenuIndicator
import com.letta.mobile.ui.components.disclosure.LettaUnfoldButton
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.components.foundation.LettaMenuItem
import com.letta.mobile.ui.components.foundation.LettaVerticalDivider
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import org.jetbrains.compose.resources.stringResource

/**
 * The card's header. On a card at least [LettaDimens.Pane.voiceCardOneLine] wide ([oneLine]) it is
 * one line: the speaker (glyph, name, timer and status), then the round controls, fold, a rule and
 * End. On a narrower card the speaker, fold and End keep the first line and the round controls take
 * a second, shown only while the card is unfolded (folded, the card is its first line). Every control
 * is [LettaControlTokens.iconButton] tall, End included.
 */
@Composable
internal fun VoiceCardHeader(handle: VoiceModeHandle, turn: VoiceCardTurn, oneLine: Boolean) {
    if (oneLine) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            VoiceCardSpeakerLine(turn)
            RoundControls(handle, turn)
            FoldControl(handle)
            LettaVerticalDivider(Modifier.height(LettaControlTokens.icon))
            EndButton(handle)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            VoiceCardSpeakerLine(turn)
            FoldControl(handle)
            EndButton(handle)
        }
        if (!handle.cardCollapsed && (handle.cardFeed.hasRoundControls() || turn.speaking)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                RoundControls(handle, turn)
            }
        }
    }
}

private fun VoiceCardFeed.hasRoundControls(): Boolean = microphoneMute != null || speakerMute != null || speed != null

/**
 * The speaker's glyph in their tint, their name, the muted timer of their turn and what voice mode is
 * doing ("Listening", "Thinking", "Speaking"): it takes the header's spare width (the name and status
 * ellipsize first), so the controls after it sit at the line's end.
 */
@Composable
private fun RowScope.VoiceCardSpeakerLine(turn: VoiceCardTurn) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        SpeakerGlyph(turn)
        Text(
            text = turn.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag(VoiceModeTags.CARD_SPEAKER),
        )
        val elapsed by rememberElapsedText(turn.elapsedMs)
        val spoken = stringResource(Res.string.voice_card_elapsed, elapsed)
        Text(
            text = elapsed,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.customColors.onSurfaceMutedColor,
            maxLines = 1,
            modifier = Modifier.testTag(VoiceModeTags.CARD_TIMER).clearAndSetSemantics { contentDescription = spoken },
        )
        Text(
            text = turn.status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag(VoiceModeTags.CARD_STATUS),
        )
    }
}

/** A microphone for the person, sound bars for the agent speaking, three pulsing dots while it thinks. */
@Composable
private fun SpeakerGlyph(turn: VoiceCardTurn) {
    val style = LettaIconStyle(LettaControlTokens.icon, turn.tint)
    when {
        turn.thinking -> ThinkingDots(turn.tint)
        turn.speaker == VoiceCardSpeaker.You -> LettaIcon(LettaIcons.Mic, contentDescription = null, style = style)
        else -> LettaIcon(LettaIcons.AudioLines, contentDescription = null, style = style)
    }
}

/** Three dots in [tint], pulsing one after another (still under reduced motion), in a glyph's box. */
@Composable
private fun ThinkingDots(tint: Color) {
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "voiceCardThinking")
    Row(
        modifier = Modifier.size(LettaControlTokens.icon),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DOTS) { dot ->
            val pulse = if (reducedMotion) {
                null
            } else {
                transition.animateFloat(
                    initialValue = DOT_DIM,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(LettaMotionTokens.CHIP_MILLIS * 2, delayMillis = dot * LettaMotionTokens.CHIP_MILLIS / 2),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "voiceCardDot$dot",
                )
            }
            Box(Modifier.size(LettaDimens.Space.xs).alpha(pulse?.value ?: 1f).background(tint, CircleShape))
        }
    }
}

/** While the reply is spoken, the round stop (interrupt and listen); then the mutes and speed the controller offers. */
@Composable
private fun RoundControls(handle: VoiceModeHandle, turn: VoiceCardTurn) {
    val feed = handle.cardFeed
    if (turn.speaking) InterruptButton(handle)
    feed.microphoneMute?.let {
        val label = stringResource(Res.string.voice_card_mic_mute)
        MuteToggle(it, VoiceCardMuteLook(VoiceModeTags.CARD_MIC, label, LettaIcons.Mic, LettaIcons.MicOff))
    }
    feed.speakerMute?.let {
        val label = stringResource(Res.string.voice_card_speaker_mute)
        MuteToggle(it, VoiceCardMuteLook(VoiceModeTags.CARD_SPEAKER_MUTE, label, LettaIcons.Volume, LettaIcons.VolumeOff))
    }
    feed.speed?.let { SpeedControl(it) }
}

/**
 * One round control: a filled circle [LettaControlTokens.iconButton] across (the platform's minimum
 * touch target around it), [glyph] in [tint], announced as a button by [semantics].
 */
@Composable
private fun RoundButton(
    glyph: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.size(LettaControlTokens.iconButton),
    ) {
        Box(contentAlignment = Alignment.Center) {
            LettaIcon(glyph, contentDescription = null, style = LettaIconStyle(LettaControlTokens.iconSm, tint))
        }
    }
}

/** Stops the reply and listens again, as the voice button did while the reply was spoken. */
@Composable
private fun InterruptButton(handle: VoiceModeHandle) {
    val label = stringResource(Res.string.voice_mode_interrupt)
    RoundButton(
        glyph = LettaIcons.Square,
        tint = MaterialTheme.colorScheme.onSurface,
        onClick = handle::press,
        modifier = Modifier.testTag(VoiceModeTags.CARD_INTERRUPT).semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = label
        },
    )
}

/** A round mute switch: its label with its state ("On" while muted), the crossed glyph in the error colour while muted. */
@Composable
private fun MuteToggle(switch: VoiceCardSwitch, look: VoiceCardMuteLook) {
    val muted by switch.on.collectAsState()
    val state = stringResource(if (muted) Res.string.voice_card_toggle_on else Res.string.voice_card_toggle_off)
    RoundButton(
        glyph = if (muted) look.mutedGlyph else look.glyph,
        tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        onClick = { switch.set(!muted) },
        modifier = Modifier.testTag(look.tag).semantics(mergeDescendants = true) {
            role = Role.Switch
            contentDescription = look.label
            toggleableState = ToggleableState(muted)
            stateDescription = state
        },
    )
}

/** The speaking speed as a round-ended pill ("1×" and the menu mark), as tall as the round controls, opening the speeds. */
@Composable
private fun SpeedControl(speed: VoiceCardSpeed) {
    val value by speed.value.collectAsState()
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.voice_card_speed)
    val shown = stringResource(Res.string.voice_card_speed_value, speedText(value.value))
    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .height(LettaControlTokens.iconButton)
                .testTag(VoiceModeTags.CARD_SPEED)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = shown
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            ) {
                Text(shown, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                LettaMenuIndicator()
            }
        }
        val items = speed.choices.map { choice -> speedItem(choice, speed) }
        LettaPopupMenu(expanded = open, onDismiss = { open = false }, items = items)
    }
}

@Composable
private fun speedItem(choice: SpeechSpeed, speed: VoiceCardSpeed): LettaMenuItem = LettaMenuItem(
    label = stringResource(Res.string.voice_card_speed_value, speedText(choice.value)),
    tag = "${VoiceModeTags.CARD_SPEED}-${speedText(choice.value)}",
    onClick = { speed.set(choice) },
)

/** A speed's words: "1", "0.75", "1.5". */
internal fun speedText(speed: Float): String {
    val hundredths = kotlin.math.round(speed * HUNDRED).toInt()
    val whole = hundredths / HUNDRED.toInt()
    val fraction = hundredths % HUNDRED.toInt()
    if (fraction == 0) return whole.toString()
    return "$whole.${fraction.toString().padStart(2, '0').trimEnd('0')}"
}

/** Fold the card to its header line, or unfold it back; as tall as the round controls. */
@Composable
private fun FoldControl(handle: VoiceModeHandle) {
    val modifier = Modifier.size(LettaControlTokens.iconButton).testTag(VoiceModeTags.CARD_COLLAPSE)
    if (handle.cardCollapsed) {
        val label = stringResource(Res.string.voice_card_expand)
        LettaUnfoldButton(onClick = { handle.cardCollapsed = false }, contentDescription = label, modifier = modifier)
    } else {
        val label = stringResource(Res.string.voice_card_collapse)
        LettaFoldButton(onClick = { handle.cardCollapsed = true }, contentDescription = label, modifier = modifier)
    }
}

/** The red End pill, as tall as the round controls: a cross and "End"; it turns voice mode off. */
@Composable
private fun EndButton(handle: VoiceModeHandle) {
    val description = stringResource(Res.string.voice_card_end_description)
    Surface(
        onClick = { handle.controller.stop() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        modifier = Modifier
            .height(LettaControlTokens.iconButton)
            .testTag(VoiceModeTags.CARD_END)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            LettaIcon(LettaIcons.Close, contentDescription = null, style = LettaIconStyle(LettaControlTokens.iconSm))
            Text(stringResource(Res.string.voice_card_end), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private const val DOTS = 3
private const val DOT_DIM = 0.35f
private const val HUNDRED = 100f
