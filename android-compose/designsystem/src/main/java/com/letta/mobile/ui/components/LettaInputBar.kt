package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons

private val ComposerActionTargetSize = 48.dp
private val ComposerActionIconSize = 20.dp
private val ComposerRestingCorner = 28.dp
private val ComposerEngagedCorner = 20.dp
private val ComposerRestingElevation = 0.dp
private val ComposerEngagedElevation = 2.dp
private const val ComposerPressedScale = 0.96f

internal data class LivingComposerState(
    val focused: Boolean,
    val text: String,
    val hasStagedContent: Boolean,
) {
    val isEngaged: Boolean
        get() = focused || text.isNotBlank() || hasStagedContent
}

private data class ComposerSendState(
    val text: String,
    val enabled: Boolean,
    val canSendOverride: Boolean?,
) {
    val canSend: Boolean
        get() = (canSendOverride ?: text.isNotBlank()) && enabled
}

private enum class ComposerMotionPreference {
    Full,
    Reduced,
}

private enum class ComposerPulseMode {
    Active,
    Static,
}

@Stable
private data class ComposerTrailingActionSpec(
    val visible: Boolean,
    val canSend: Boolean,
    val text: String,
    val onSend: (String) -> Unit,
    val icon: ImageVector,
    val contentDescription: String,
    val containerColor: Color,
    val contentColor: Color,
    val iconSize: Dp,
    val visualScale: State<Float>,
    val pulseScale: State<Float>,
    val motionPreference: ComposerMotionPreference,
    val customContent: (@Composable () -> Unit)?,
)

private data class ComposerActionIconSpec(
    val icon: ImageVector,
    val contentDescription: String,
    val size: Dp,
    val motionPreference: ComposerMotionPreference,
)

private data class ComposerSurfaceColors(
    val resting: Color,
    val engaged: Color,
)

private data class ComposerVisualTargets(
    val corner: Dp,
    val elevation: Dp,
    val color: Color,
)

private fun composerVisualTargets(
    state: LivingComposerState,
    colors: ComposerSurfaceColors,
): ComposerVisualTargets = if (state.isEngaged) {
    ComposerVisualTargets(
        corner = ComposerEngagedCorner,
        elevation = ComposerEngagedElevation,
        color = colors.engaged,
    )
} else {
    ComposerVisualTargets(
        corner = ComposerRestingCorner,
        elevation = ComposerRestingElevation,
        color = colors.resting,
    )
}

private fun <T> composerMotionSpec(
    preference: ComposerMotionPreference,
    animatedSpec: FiniteAnimationSpec<T>,
): FiniteAnimationSpec<T> =
    if (preference == ComposerMotionPreference.Reduced) snap() else animatedSpec

/**
 * Shared pill-shaped input bar with send button.
 *
 * Used by both the chat screen and the homepage chat field.
 *
 * @param text Current text value.
 * @param onTextChange Called when the text changes.
 * @param onSend Called with the current text when the user hits send.
 * @param placeholder Placeholder text shown when empty.
 * @param sendContentDescription Accessibility label for the send button.
 * @param enabled Whether the send button is enabled (beyond the default non-blank check).
 * @param maxLines Maximum visible lines for the text field.
 * @param canSendOverride Optional override for the send enablement check —
 *   useful when the bar has non-text content staged (e.g. image attachments)
 *   so Send is enabled with an empty text field.
 * @param leadingContent Optional slot rendered to the left of the text field,
 *   typically an attach button.
 * @param customTrailingContent Optional override for the trailing action.
 *   When non-null, this composable replaces the built-in Send/Stop button
 *   entirely and is responsible for its own sizing, click handling, and
 *   animations. Used by ChatComposer to swap in the HoldToDictateButton
 *   when the text field is empty (letta-mobile-rl0d follow-up). The
 *   built-in actionVisible/pulse/icon logic still applies to the slot's
 *   container, so the slot inherits show/hide animations for free.
 * @param actionPulse When true, applies a subtle ~800ms heartbeat scale-pulse
 *   to the action button to communicate that work is in progress (e.g. an
 *   active assistant stream behind the Stop button). Suppressed entirely when
 *   the user has reduced motion enabled.
 * @param actionVisible When false, the trailing action button slides out
 *   horizontally and the text field expands to fill the freed space. Use this
 *   to defer to the IME's own Send action while the soft keyboard is open and
 *   no in-flight work needs to be cancelable. Defaults to true so existing
 *   call sites keep their current behaviour.
 * @param hasStagedContent Whether non-text content, such as an attachment,
 *   should keep the bar in its engaged visual state.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LettaInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    placeholder: String,
    sendContentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 4,
    canSendOverride: Boolean? = null,
    actionIcon: ImageVector = LettaIcons.Send,
    actionContentDescription: String = sendContentDescription,
    actionContainerColor: Color? = null,
    actionContentColor: Color? = null,
    actionSizeFraction: Float = 1f,
    actionPulse: Boolean = false,
    actionVisible: Boolean = true,
    hasStagedContent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    itemSpacing: Dp = 8.dp,
    leadingContent: (@Composable () -> Unit)? = null,
    customTrailingContent: (@Composable () -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val reducedMotion = rememberReducedMotionEnabled()
    val motionPreference = if (reducedMotion) {
        ComposerMotionPreference.Reduced
    } else {
        ComposerMotionPreference.Full
    }
    val canSend = ComposerSendState(
        text = text,
        enabled = enabled,
        canSendOverride = canSendOverride,
    ).canSend
    val focused = remember { mutableStateOf(false) }
    val livingState = LivingComposerState(
        focused = focused.value,
        text = text,
        hasStagedContent = hasStagedContent,
    )
    val visualTargets = composerVisualTargets(
        state = livingState,
        colors = ComposerSurfaceColors(
            resting = colorScheme.surfaceContainerLow,
            engaged = colorScheme.surfaceContainer,
        ),
    )
    val composerCorner by animateDpAsState(
        targetValue = visualTargets.corner,
        animationSpec = composerMotionSpec(
            motionPreference,
            MaterialTheme.motionScheme.fastSpatialSpec(),
        ),
        label = "inputComposerCorner",
    )
    val composerElevation by animateDpAsState(
        targetValue = visualTargets.elevation,
        animationSpec = composerMotionSpec(
            motionPreference,
            MaterialTheme.motionScheme.fastSpatialSpec(),
        ),
        label = "inputComposerElevation",
    )
    val composerColor by animateColorAsState(
        targetValue = visualTargets.color,
        animationSpec = composerMotionSpec(
            motionPreference,
            MaterialTheme.motionScheme.fastEffectsSpec(),
        ),
        label = "inputComposerColor",
    )
    val actionVisualScale = animateFloatAsState(
        targetValue = actionSizeFraction.coerceIn(0.7f, 1f),
        animationSpec = composerMotionSpec(
            motionPreference,
            MaterialTheme.motionScheme.fastSpatialSpec(),
        ),
        label = "inputActionVisualScale",
    )
    val actionIconSize by animateDpAsState(
        targetValue = ComposerActionIconSize * actionSizeFraction.coerceIn(0.7f, 1f),
        animationSpec = composerMotionSpec(
            motionPreference,
            MaterialTheme.motionScheme.fastSpatialSpec(),
        ),
        label = "inputActionIconSize",
    )

    // letta-mobile-d9zy.5 (retry): subtle heartbeat pulse on the action
    // button. Replaces the 2026-05-12 attempt to ring the button with a
    // CircularProgressIndicator (rejected for misalignment). A 1.0 → 1.04
    // → 1.0 scale tween is small enough not to crowd the input row but
    // still rhythmic enough to read as "active". Skipped under reduced
    // motion so the button stays static. The pulse is applied through
    // graphicsLayer rather than layout, so the touch target stays at the
    // baseline 48 dp regardless of phase.
    val actionPulseScale = rememberActionPulseScale(
        mode = if (actionPulse && !reducedMotion) {
            ComposerPulseMode.Active
        } else {
            ComposerPulseMode.Static
        },
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(composerCorner),
        color = composerColor,
        tonalElevation = composerElevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            leadingContent?.let { content ->
                Box(modifier = Modifier.align(Alignment.Bottom)) {
                    content()
                }
            }
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused.value = it.isFocused },
                placeholder = {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = colorScheme.onSurface,
                ),
                maxLines = maxLines,
                singleLine = maxLines == 1,
                shape = RoundedCornerShape(composerCorner),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = colorScheme.primary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend) {
                            HapticEffects.confirm(haptic, view)
                            onSend(text)
                        }
                    },
                ),
            )

            ComposerTrailingAction(
                spec = ComposerTrailingActionSpec(
                    visible = actionVisible,
                    canSend = canSend,
                    text = text,
                    onSend = onSend,
                    icon = actionIcon,
                    contentDescription = actionContentDescription,
                    containerColor = actionContainerColor ?: colorScheme.primary,
                    contentColor = actionContentColor ?: colorScheme.onPrimary,
                    iconSize = actionIconSize,
                    visualScale = actionVisualScale,
                    pulseScale = actionPulseScale,
                    motionPreference = motionPreference,
                    customContent = customTrailingContent,
                ),
                modifier = Modifier.align(Alignment.Bottom),
            )
        }
    }
}

/**
 * Keeps visibility, custom-slot dispatch, and icon morph decisions out of the
 * input field's composition path. Animated scale values remain deferred to the
 * graphics layer so heartbeat frames do not recompose the button.
 */
@Composable
private fun ComposerTrailingAction(
    spec: ComposerTrailingActionSpec,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    AnimatedVisibility(
        visible = spec.visible,
        enter = composerActionEnterTransition(spec.motionPreference),
        exit = composerActionExitTransition(spec.motionPreference),
        modifier = modifier,
        label = "inputActionVisibility",
    ) {
        spec.customContent?.let {
            it()
            return@AnimatedVisibility
        }
        FilledIconButton(
            onClick = {
                HapticEffects.confirm(haptic, view)
                spec.onSend(spec.text)
            },
            enabled = spec.canSend,
            modifier = Modifier
                .size(ComposerActionTargetSize)
                .graphicsLayer {
                    val scale = spec.visualScale.value * spec.pulseScale.value
                    scaleX = scale
                    scaleY = scale
                },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = spec.containerColor,
                contentColor = spec.contentColor,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            ),
        ) {
            ComposerActionIcon(
                spec = ComposerActionIconSpec(
                    icon = spec.icon,
                    contentDescription = spec.contentDescription,
                    size = spec.iconSize,
                    motionPreference = spec.motionPreference,
                ),
            )
        }
    }
}

@Composable
private fun composerActionEnterTransition(
    preference: ComposerMotionPreference,
): EnterTransition =
    if (preference == ComposerMotionPreference.Reduced) {
        fadeIn(tween(durationMillis = 0))
    } else {
        fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
            expandHorizontally(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                expandFrom = Alignment.End,
            )
    }

@Composable
private fun composerActionExitTransition(
    preference: ComposerMotionPreference,
): ExitTransition =
    if (preference == ComposerMotionPreference.Reduced) {
        fadeOut(tween(durationMillis = 0))
    } else {
        fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
            shrinkHorizontally(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                shrinkTowards = Alignment.End,
            )
    }

@Composable
private fun ComposerActionIcon(
    spec: ComposerActionIconSpec,
) {
    if (spec.motionPreference == ComposerMotionPreference.Reduced) {
        Icon(
            spec.icon,
            contentDescription = spec.contentDescription,
            modifier = Modifier.size(spec.size),
        )
        return
    }
    val iconTransition =
        (fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
            scaleIn(
                initialScale = 0.76f,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ))
            .togetherWith(
                fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
                    scaleOut(
                        targetScale = 0.76f,
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    ),
            )
    AnimatedContent(
        targetState = spec.icon,
        transitionSpec = { iconTransition },
        label = "inputActionIconMorph",
    ) { targetIcon ->
        Icon(
            targetIcon,
            contentDescription = spec.contentDescription,
            modifier = Modifier.size(spec.size),
        )
    }
}

@Composable
private fun rememberActionPulseScale(mode: ComposerPulseMode): State<Float> {
    if (mode == ComposerPulseMode.Static) return remember { mutableFloatStateOf(1f) }
    val pulseTransition = rememberInfiniteTransition(label = "actionHeartbeat")
    return pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "actionHeartbeatScale",
    )
}

/**
 * Returns a draw-layer press scale for compact floating controls.
 *
 * The caller keeps its layout and semantic target unchanged and applies the
 * returned value through [Modifier.graphicsLayer].
 */
@Composable
fun rememberFloatingControlPressScale(
    interactionSource: MutableInteractionSource,
    reducedMotion: Boolean,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) ComposerPressedScale else 1f,
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "floatingControlPressScale",
    )
    return scale
}
