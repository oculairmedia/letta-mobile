package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_actions_open
import com.letta.mobile.sharedui.resources.composer_attach_image
import com.letta.mobile.sharedui.resources.composer_open_canvas
import com.letta.mobile.sharedui.resources.composer_send
import com.letta.mobile.sharedui.resources.composer_stop
import com.letta.mobile.sharedui.resources.composer_stopping
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.TouchComposerDimens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-bglj6.1.9: the shared page's composer in the Touch idiom, a port of the legacy
 * Android composer (feature-chat ChatComposer + designsystem LettaInputBar): a flush, full-width
 * bar with only its top corners rounded, a round tonal "+" on the left, the prompt field, and one
 * slot on the right that holds the platform's hold-to-dictate mic while the draft is empty, Send
 * once there is something to send, and a red Stop while a run streams. It runs to the bottom edge
 * of the screen: the gesture bar draws over its padding.
 */

private val TouchFieldStyle = ComposerFieldStyle(
    testTag = ComposerTestTags.INPUT,
    maxHeight = ChatComposerDimens.promptMaxHeight,
    singleLine = false,
    maxLines = 4,
    imeSend = true,
    touchPlaceholder = true,
)

/**
 * The Touch bar with what stands above it: the staged images. [leading] goes before the "+" (the
 * canvas bar's way back to the reply). [docked] is the canvas's bar, which keeps the field to its
 * test tag for the docked input.
 */
@Composable
internal fun TouchComposerBar(
    model: ComposerModel,
    onAttachImage: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val sheetItems = touchSheetItems(model, onAttachImage)
    Column(modifier.fillMaxWidth().then(touchBarSwipe(model))) {
        TouchAttachments(model)
        TouchBarSurface(model) {
            leading?.invoke()
            TouchPlusButton(
                items = sheetItems,
                onClick = { if (sheetItems.size == 1) sheetItems.single().onClick() else sheetOpen = true },
            )
            Box(Modifier.weight(1f).padding(horizontal = TouchComposerDimens.fieldPadding, vertical = TouchComposerDimens.fieldPadding)) {
                ComposerTextField(model = model, style = touchFieldStyle(model))
            }
            TouchTrailingSlot(model)
        }
    }
    if (sheetOpen) TouchComposerActionSheet(items = sheetItems, onDismiss = { sheetOpen = false })
}

/** Swipe the bar up: on the canvas it opens the chat; on the chat page, the canvas (legacy). */
@Composable
private fun touchBarSwipe(model: ComposerModel): Modifier {
    val docked = model.mode == ChatSurfaceMode.Docked
    val keyboard = keyboardOpen()
    val toCanvas = model.offersOpenCanvas && !model.streaming
    val swipe = if (docked) !keyboard else toCanvas && !keyboard
    return Modifier.swipeUpToCanvas(enabled = swipe) {
        model.onIntent(if (docked) ChatSurfaceIntent.Expand else ChatSurfaceIntent.OpenCanvas)
    }
}

/** The staged images, above the bar. */
@Composable
private fun TouchAttachments(model: ComposerModel) {
    if (model.composer.attachments.isEmpty()) return
    Box(Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs)) {
        ComposerAttachmentStrip(attachments = model.composer.attachments, onRemove = model.actions::removeAttachment)
    }
}

/** The canvas's bar keeps the field to its test tag for the docked input. */
private fun touchFieldStyle(model: ComposerModel): ComposerFieldStyle {
    return if (model.mode == ChatSurfaceMode.Docked) TouchFieldStyle.copy(testTag = ComposerTestTags.DOCKED_INPUT) else TouchFieldStyle
}

/** The bar's look, at rest or engaged. */
@Immutable
private class TouchBarLook(val corner: Dp, val elevation: Dp, val color: Color)

/** The bar is engaged while it has focus or something to send; its corners, elevation and fill ease between the two looks. */
@Composable
private fun animatedTouchBarLook(model: ComposerModel, focused: Boolean): TouchBarLook {
    val scheme = MaterialTheme.colorScheme
    val drafted = model.composer.text.isNotBlank() || model.composer.attachments.isNotEmpty()
    val engaged = focused || drafted
    val spec = if (LocalReducedMotion.current) snap() else tween<Dp>(LettaMotionTokens.CHIP_MILLIS)
    val corner by animateDpAsState(
        if (engaged) TouchComposerDimens.engagedCorner else TouchComposerDimens.restingCorner,
        spec,
        label = "touchBarCorner",
    )
    val elevation by animateDpAsState(
        if (engaged) TouchComposerDimens.engagedElevation else TouchComposerDimens.restingElevation,
        spec,
        label = "touchBarElevation",
    )
    val color by animateColorAsState(
        if (engaged) scheme.surfaceContainer else scheme.surfaceContainerLow,
        chipSpec(),
        label = "touchBarColor",
    )
    return TouchBarLook(corner, elevation, color)
}

/** The bar's surface: top corners only, easing from its resting to its engaged look. */
@Composable
private fun TouchBarSurface(model: ComposerModel, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val look = animatedTouchBarLook(model, focused)
    val padding = touchBarPadding()
    Surface(
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.testTag(ComposerTestTags.TOUCH_BAR),
        shape = RoundedCornerShape(topStart = look.corner, topEnd = look.corner),
        color = look.color,
        contentColor = scheme.onSurface,
        tonalElevation = look.elevation,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = TouchComposerDimens.horizontalPadding,
                end = TouchComposerDimens.horizontalPadding,
                top = padding.first,
                bottom = padding.second,
            ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(TouchComposerDimens.itemSpacing),
            content = content,
        )
    }
}

/** The chips' easing: their own duration, or none under reduced motion. */
@Composable
private fun <T> chipSpec(): AnimationSpec<T> {
    return if (LocalReducedMotion.current) snap() else tween(LettaMotionTokens.CHIP_MILLIS)
}

/**
 * Top and bottom padding. Legacy: 24 dp at rest, easing to 12 dp as the keyboard rises (driven by
 * the inset itself, so it tracks the keyboard frame by frame). The bar runs flush to the screen's
 * bottom edge (legacy ChatScreen's bottomInsetDp = 0, letta-mobile-bglj6.1.9): the opaque bar sits
 * under the navigation bar, which draws over its bottom padding, instead of growing by the
 * navigation inset and floating its controls ~48 dp up on three-button devices.
 */
@Composable
private fun touchBarPadding(): Pair<Dp, Dp> {
    val imePx = WindowInsets.ime.getBottom(LocalDensity.current)
    val vertical = touchBarVerticalPadding(imePx)
    return vertical to vertical
}

/** The bar's vertical padding for an IME inset of [imePx]: resting, easing to compact as it rises. */
internal fun touchBarVerticalPadding(imePx: Int): Dp {
    val compact = (imePx / TouchComposerDimens.imeInsetForCompactPx).coerceIn(0f, 1f)
    return lerp(TouchComposerDimens.restingVerticalPadding, TouchComposerDimens.compactVerticalPadding, compact)
}

/**
 * The round tonal "+": the action sheet, or (with one thing to do) that thing directly. It is
 * announced as what it does (letta-mobile-bglj6.1.9): "Add to message" when it opens the sheet,
 * the single item's own label ("Attach image") when it does that directly, as legacy.
 */
@Composable
private fun TouchPlusButton(items: List<TouchSheetItem>, onClick: () -> Unit) {
    if (items.isEmpty()) return
    val description = items.singleOrNull()?.label ?: stringResource(Res.string.composer_actions_open)
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && !reducedMotion) TouchComposerDimens.pressedScale else 1f,
        chipSpec(),
        label = "touchPlusScale",
    )
    Box(
        modifier = Modifier
            .size(TouchComposerDimens.actionTarget)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .testTag(ComposerTestTags.TOUCH_PLUS),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(TouchComposerDimens.attachButton).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    LettaIcons.Add,
                    contentDescription = description,
                    modifier = Modifier.size(TouchComposerDimens.attachIcon),
                )
            }
        }
    }
}

/** What the "+" offers: attach an image; open the canvas where the page has one to open. */
@Composable
private fun touchSheetItems(model: ComposerModel, onAttachImage: () -> Unit): List<TouchSheetItem> {
    val attach = stringResource(Res.string.composer_attach_image)
    val canvas = stringResource(Res.string.composer_open_canvas)
    return buildList {
        if (model.capabilities.attachImages) add(TouchSheetItem(attach, LettaIcons.Add, onAttachImage))
        if (model.offersOpenCanvas) add(TouchSheetItem(canvas, LettaIcons.Edit) { model.onIntent(ChatSurfaceIntent.OpenCanvas) })
    }
}

/**
 * The slot is the mic: the platform can dictate, nothing is drafted or running, and the field takes
 * input (no dictating into a field that takes none: the slot falls back to the greyed Send, legacy).
 */
private fun touchSlotDictates(model: ComposerModel): Boolean {
    if (model.platform.voiceInput == null || !model.composer.acceptsInput) return false
    return !model.streaming && !model.composer.hasPayload
}

/**
 * The right-hand slot. Empty and idle, it is the platform's hold-to-dictate mic; otherwise Send,
 * or Stop while a run streams with nothing to queue. While the keyboard is up Send steps aside
 * (the keyboard's own action key sends), but Stop and the mic stay.
 */
@Composable
private fun TouchTrailingSlot(model: ComposerModel) {
    val dictates = touchSlotDictates(model)
    val keyboard = keyboardOpen()
    val visible = dictates || model.streaming || !keyboard
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(LettaMotionTokens.CHIP_MILLIS)) + expandHorizontally(expandFrom = Alignment.End),
        exit = if (reducedMotion) {
            fadeOut(snap())
        } else {
            fadeOut(tween(LettaMotionTokens.CHIP_MILLIS)) + scaleOut(targetScale = EXIT_SCALE) + shrinkHorizontally(shrinkTowards = Alignment.End)
        },
        label = "touchTrailingSlot",
    ) {
        if (dictates) {
            Box(Modifier.size(TouchComposerDimens.actionTarget).testTag(ComposerTestTags.TOUCH_VOICE), contentAlignment = Alignment.Center) {
                ComposerVoiceButton(model)
            }
        } else {
            TouchActionButton(model)
        }
    }
}

private const val EXIT_SCALE = 0.76f

/** Send, or the red Stop (smaller, beating while the run is live; "stopping" once asked). */
@Composable
private fun TouchActionButton(model: ComposerModel) {
    val stops = model.decisions.action == ComposerAction.Stop
    val haptics = LocalHapticFeedback.current
    val size by animateFloatAsState(
        if (stops) TouchComposerDimens.stopScale else 1f,
        chipSpec(),
        label = "touchActionScale",
    )
    val pulse = rememberStopPulse(stopPulses(model))
    FilledIconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            model.runAction()
        },
        enabled = stops || model.decisions.sendEnabled,
        modifier = Modifier
            .size(TouchComposerDimens.actionTarget)
            .graphicsLayer {
                val scale = size * pulse.value
                scaleX = scale
                scaleY = scale
            }
            .testTag(if (stops) ComposerTestTags.STOP else ComposerTestTags.SEND),
        colors = touchActionColors(stops),
    ) {
        Icon(
            imageVector = if (stops) LettaIcons.Close else LettaIcons.Send,
            contentDescription = stringResource(touchActionLabel(model.decisions)),
            modifier = Modifier.size(TouchComposerDimens.actionIcon),
        )
    }
}

/** Stop beats while the run is live and not yet asked to stop; never under reduced motion. */
@Composable
private fun stopPulses(model: ComposerModel): Boolean {
    if (LocalReducedMotion.current) return false
    return model.decisions.action == ComposerAction.Stop && !model.decisions.stopping
}

/** Send in the primary colour; Stop in the error container. */
@Composable
private fun touchActionColors(stops: Boolean): IconButtonColors {
    val scheme = MaterialTheme.colorScheme
    return IconButtonDefaults.filledIconButtonColors(
        containerColor = if (stops) scheme.errorContainer else scheme.primary,
        contentColor = if (stops) scheme.onErrorContainer else scheme.onPrimary,
        disabledContainerColor = scheme.surfaceContainerHigh,
        disabledContentColor = scheme.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.disabled),
    )
}

/** "Stopping" once asked, "Stop" during a run, otherwise "Send". */
private fun touchActionLabel(decisions: ComposerDecisions): StringResource {
    return when {
        decisions.stopping -> Res.string.composer_stopping
        decisions.action == ComposerAction.Stop -> Res.string.composer_stop
        else -> Res.string.composer_send
    }
}

/** A gentle 1.0 -> 1.04 heartbeat while [active]; still otherwise. */
@Composable
private fun rememberStopPulse(active: Boolean): State<Float> {
    if (!active) return remember { mutableFloatStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "touchStopPulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = TouchComposerDimens.stopPulseScale,
        animationSpec = infiniteRepeatable(
            animation = tween(TouchComposerDimens.stopPulseMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "touchStopPulseScale",
    )
}
