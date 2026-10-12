package com.letta.mobile.ui.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion

/** The shared containers every host starts from. Platforms override single entries on [LettaHost]. */
object LettaPaneDefaults {
    val Sheet: PaneContainer = { spec, content -> SheetContainer(spec, content) }
    val Popover: PaneContainer = { spec, content -> PopoverContainer(spec, content) }
    val Dialog: PaneContainer = { spec, content -> DialogContainer(spec, LettaDimens.Pane.modalWidth, LettaPaneTags.DIALOG, content) }
    val Alert: PaneContainer = { spec, content -> DialogContainer(spec, LettaDimens.Pane.alertWidth, LettaPaneTags.ALERT, content) }
    val Menu: MenuContainer = { expanded, spec, content -> MenuContainer(expanded, spec, content) }
}

@Composable
private fun SheetContainer(spec: PaneSpec, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = spec.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = spec.modifier.testTag(LettaPaneTags.SHEET),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().then(rememberPaneFocus(spec.onDismiss)).tagged(spec.testTag)) {
            PaneTitle(spec.title)
            content()
        }
    }
}

@Composable
private fun PopoverContainer(spec: PaneSpec, content: @Composable ColumnScope.() -> Unit) {
    val gapPx = with(LocalDensity.current) { LettaDimens.Space.sm.roundToPx() }
    val provider = remember(spec.anchor, gapPx) { AnchoredPanePositionProvider(spec.anchor, gapPx) }
    Popup(popupPositionProvider = provider, onDismissRequest = spec.onDismiss, properties = PopupProperties(focusable = true)) {
        PaneEnter { LettaPopoverFrame(spec, content) }
    }
}

@Composable
private fun DialogContainer(spec: PaneSpec, width: Dp, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = spec.onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PaneEnter { LettaDialogFrame(spec, width, tag, content) }
    }
}

@Composable
private fun MenuContainer(expanded: Boolean, spec: PaneSpec, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = spec.onDismiss,
        modifier = spec.modifier,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

/**
 * A dialog's visual frame without its window: a rounded card with the title over the content.
 * Containers draw it inside their window; review renders draw it in place.
 */
@Composable
fun LettaDialogFrame(
    spec: PaneSpec,
    width: Dp = LettaDimens.Pane.modalWidth,
    tag: String = LettaPaneTags.DIALOG,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = spec.modifier
            .padding(LettaDimens.Space.lg)
            .widthIn(max = spec.size.width.orElse(width))
            .heightIn(max = spec.size.maxHeight.orElse(LettaDimens.Pane.modalMaxHeight))
            .testTag(tag),
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = LettaDimens.Space.sm,
    ) {
        Column(Modifier.then(rememberPaneFocus(spec.onDismiss)).tagged(spec.testTag)) {
            PaneTitle(spec.title)
            content()
        }
    }
}

/** A popover's visual frame: a compact raised card, sized by [PaneSpec.size] when given. */
@Composable
fun LettaPopoverFrame(spec: PaneSpec, content: @Composable ColumnScope.() -> Unit) {
    val width = spec.size.width
    Surface(
        modifier = spec.modifier
            .then(if (width == Dp.Unspecified) Modifier.widthIn(max = LettaDimens.Pane.sidePanelWidth) else Modifier.width(width))
            .heightIn(max = spec.size.maxHeight.orElse(LettaDimens.Pane.modalMaxHeight))
            .testTag(LettaPaneTags.POPOVER),
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = LettaDimens.Space.sm,
    ) {
        Column(Modifier.padding(vertical = LettaDimens.Space.sm).then(rememberPaneFocus(spec.onDismiss)).tagged(spec.testTag)) {
            PaneTitle(spec.title)
            content()
        }
    }
}

@Composable
private fun PaneTitle(title: String?) {
    title ?: return
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = LettaDimens.Space.xl, end = LettaDimens.Space.xl, top = LettaDimens.Space.lg, bottom = LettaDimens.Space.md),
    )
}

/** Enters with a short fade and settle, or at once under reduced motion. */
@Composable
private fun PaneEnter(content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val visible = remember { MutableTransitionState(reduced).apply { targetState = true } }
    val enter = if (reduced) {
        EnterTransition.None
    } else {
        fadeIn(tween(LettaMotionTokens.ENTER_MILLIS)) + scaleIn(tween(LettaMotionTokens.ENTER_MILLIS), initialScale = ENTER_SCALE)
    }
    AnimatedVisibility(visibleState = visible, enter = enter, exit = ExitTransition.None) { content() }
}

/**
 * Focus moves into the pane when it opens (so keys reach it and Tab stays inside its window), and
 * Escape asks to dismiss it. A field inside that requests focus itself still wins: its request runs
 * after this one.
 */
@Composable
internal fun rememberPaneFocus(onDismiss: () -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return Modifier
        .onPreviewKeyEvent { event ->
            val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
            if (escape) onDismiss()
            escape
        }
        .focusRequester(requester)
        .focusable()
}

private fun Modifier.tagged(tag: String?): Modifier = if (tag == null) this else testTag(tag)

private fun Dp.orElse(fallback: Dp): Dp = if (this == Dp.Unspecified) fallback else this

private const val ENTER_SCALE = 0.96f

/** Places an anchored pane at [anchor] of its declaring composable, [gapPx] away, kept inside the window. */
internal class AnchoredPanePositionProvider(private val anchor: PaneAnchor, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val startAligned = anchor == PaneAnchor.BelowStart || anchor == PaneAnchor.AboveStart
        val leftAligned = startAligned == (layoutDirection == LayoutDirection.Ltr)
        val x = if (leftAligned) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val below = anchor == PaneAnchor.BelowStart || anchor == PaneAnchor.BelowEnd
        val y = if (below) anchorBounds.bottom + gapPx else anchorBounds.top - gapPx - popupContentSize.height
        return IntOffset(
            x.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
            y.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0),
        )
    }
}
