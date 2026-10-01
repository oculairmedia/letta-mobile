package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Minimize2
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Square
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_show_canvas
import com.letta.mobile.sharedui.resources.composer_attach
import com.letta.mobile.sharedui.resources.composer_attach_images
import com.letta.mobile.sharedui.resources.composer_expand
import com.letta.mobile.sharedui.resources.composer_open_canvas
import com.letta.mobile.sharedui.resources.composer_send
import com.letta.mobile.sharedui.resources.composer_stop
import com.letta.mobile.sharedui.resources.composer_stopping
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import org.jetbrains.compose.resources.stringResource

/**
 * The prompt card's control row: the plus (attach / open canvas), the model and effort chips,
 * the context chip, the platform's voice button and the send/stop button. Below
 * [ChatComposerDimens.controlsWrapBreakpoint] the context chip wraps to a second row so the
 * primary controls and Send stay reachable. Lifted from desktop's ComposerControlRow.
 */
@Composable
internal fun ComposerControlRow(model: ComposerModel, onAttachImage: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag(ComposerTestTags.CONTROLS)) {
        val narrow = maxWidth < ChatComposerDimens.controlsWrapBreakpoint
        val usage = model.composer.contextUsage
        Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            ControlsRow {
                if (model.offersOpenCanvas) ComposerShowCanvasButton(onShowCanvas = { model.onIntent(ChatSurfaceIntent.OpenCanvas) })
                ComposerPlusButton(model, onAttachImage)
                if (model.showModel) model.composer.model?.let { ComposerModelControls(it, model.actions, model.host) }
                if (!narrow && usage != null) ComposerContextChip(usage)
                Spacer(Modifier.weight(1f))
                ComposerVoiceButton(model)
                ComposerActionButton(model)
            }
            if (narrow && usage != null) ControlsRow { ComposerContextChip(usage) }
        }
    }
}

@Composable
private fun ControlsRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The plus. With one thing to do (attach) it does it; with more (attach, open canvas) it opens
 * a small menu. Replaces Android's action sheet and desktop's plus menu with one control.
 */
@Composable
internal fun ComposerPlusButton(model: ComposerModel, onAttachImage: () -> Unit) {
    val items = composerPlusItems(model, onAttachImage)
    if (items.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    val single = items.singleOrNull()
    Box {
        ComposerIconButton(
            icon = LettaIcons.Add,
            contentDescription = stringResource(Res.string.composer_attach),
            onClick = { if (single != null) single.onClick() else menuOpen = true },
        )
        LettaPopupMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, items = items)
    }
}

@Composable
private fun composerPlusItems(model: ComposerModel, onAttachImage: () -> Unit): List<LettaMenuItem> {
    val attachLabel = stringResource(Res.string.composer_attach_images)
    val canvasLabel = stringResource(Res.string.composer_open_canvas)
    return buildList {
        if (model.capabilities.attachImages) {
            add(LettaMenuItem(label = attachLabel, icon = Lucide.Image, onClick = onAttachImage))
        }
        if (model.offersOpenCanvas) {
            add(LettaMenuItem(label = canvasLabel, icon = Lucide.Palette, onClick = { model.onIntent(ChatSurfaceIntent.OpenCanvas) }))
        }
    }
}

@Composable
internal fun ComposerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(LettaDimens.Control.iconButton)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(LettaDimens.Control.icon),
            // onSurface, not onSurfaceVariant: this is an action, and on the composer's own
            // container the muted role sank into it.
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The docked bar's expand affordance: opens the full chat page. */
@Composable
internal fun ComposerExpandButton(onExpand: () -> Unit) {
    ComposerIconButton(
        icon = Lucide.Maximize2,
        contentDescription = stringResource(Res.string.composer_expand),
        onClick = onExpand,
        modifier = Modifier.testTag(ComposerTestTags.EXPAND),
    )
}

/**
 * The full page's way back to the canvas, where the docked bar keeps its expand control:
 * Minimize2 here, Maximize2 there, so the pair reads as one toggle. Raises
 * [ChatSurfaceIntent.OpenCanvas], like the swipe up and the plus menu's item.
 */
@Composable
internal fun ComposerShowCanvasButton(onShowCanvas: () -> Unit) {
    ComposerIconButton(
        icon = Lucide.Minimize2,
        contentDescription = stringResource(Res.string.chat_surface_show_canvas),
        onClick = onShowCanvas,
        modifier = Modifier.testTag(ComposerTestTags.SHOW_CANVAS),
    )
}

/** The platform's dictation button (Android's speech recognizer); dictated text joins the draft. */
@Composable
internal fun ComposerVoiceButton(model: ComposerModel) {
    val voice = model.platform.voiceInput ?: return
    if (model.streaming) return
    val currentText by rememberUpdatedState(model.composer.text)
    val actions = model.actions
    voice { dictated -> actions.updateComposerText(draftAfterDictation(currentText, dictated)) }
}

/**
 * Send, or Stop while a run streams with nothing to queue. A pending stop reads "stopping"
 * and a second press is the owner's local force-clear. Lifted from desktop's ComposerSendButton
 * and Android's stop morph.
 */
@Composable
internal fun ComposerActionButton(model: ComposerModel) {
    val stops = model.decisions.action == ComposerAction.Stop
    val enabled = stops || model.decisions.sendEnabled
    val colors = actionButtonColors(stops = stops, enabled = enabled)
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else DisabledActionScale,
        animationSpec = tween(durationMillis = LettaMotionTokens.CHIP_MILLIS),
        label = "composerActionScale",
    )
    Surface(
        onClick = model::runAction,
        enabled = enabled,
        modifier = Modifier
            .size(LettaDimens.Control.actionButton)
            .testTag(if (stops) ComposerTestTags.STOP else ComposerTestTags.SEND)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = CircleShape,
        color = colors.first,
        contentColor = colors.second,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (stops) Lucide.Square else Lucide.ArrowUp,
                contentDescription = actionDescription(stops, model.decisions.stopping),
                modifier = Modifier.size(if (stops) LettaDimens.Control.iconSm else LettaDimens.Control.icon),
            )
        }
    }
}

private const val DisabledActionScale = 0.9f

@Composable
private fun actionDescription(stops: Boolean, stopping: Boolean): String = when {
    stopping -> stringResource(Res.string.composer_stopping)
    stops -> stringResource(Res.string.composer_stop)
    else -> stringResource(Res.string.composer_send)
}

/** Container and content colours; animated so Send lights up as the draft becomes sendable. */
@Composable
private fun actionButtonColors(stops: Boolean, enabled: Boolean): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        stops -> scheme.errorContainer
        enabled -> scheme.primary
        else -> scheme.surfaceContainerHigh
    }
    // Not a halved alpha when disabled: onSurfaceVariant is already the muted role.
    val content = when {
        stops -> scheme.onErrorContainer
        enabled -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    val spec = tween<Color>(durationMillis = LettaMotionTokens.CHIP_MILLIS)
    val animatedContainer by animateColorAsState(container, spec, label = "composerActionContainer")
    val animatedContent by animateColorAsState(content, spec, label = "composerActionContent")
    return animatedContainer to animatedContent
}
