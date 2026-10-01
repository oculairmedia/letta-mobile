package com.letta.mobile.ui.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * How large text-selection handles are drawn, as a multiple of the platform's. A phone's are
 * sized for a finger already; a desktop's are mouse-sized, and a fingertip misses them.
 */
val LocalSelectionHandleScale = staticCompositionLocalOf { 1f }

/**
 * A [SelectionContainer] whose handles are drawn at [LocalSelectionHandleScale].
 *
 * Compose sizes a handle from the density it is composed at, and offers nothing else to size it
 * by. The handles belong to the container, not to its text, so the container runs at the scaled
 * density while its [content] and its right-click menu get the real one back: bigger handles,
 * and the same text and menu. Handles only appear for touch, so a mouse sees no difference.
 */
@Composable
fun LettaSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale = LocalSelectionHandleScale.current
    if (scale == 1f) {
        SelectionContainer(modifier, content)
        return
    }
    val density = LocalDensity.current
    val handleDensity = remember(density, scale) { Density(density.density * scale, density.fontScale) }
    // The modifier is laid out at the real density; only the container inside is scaled.
    Box(modifier) {
        CompositionLocalProvider(LocalDensity provides handleDensity) {
            KeepMenusAtDensity(density) {
                SelectionContainer {
                    CompositionLocalProvider(LocalDensity provides density, content = content)
                }
            }
        }
    }
}

/** Menus the container draws itself (a desktop's right-click menu) keep [density]. */
@Composable
internal expect fun KeepMenusAtDensity(density: Density, content: @Composable () -> Unit)

/**
 * Copy, cut, paste and select-all as a bar of finger-sized buttons over what was selected by
 * touch: Compose asks its [TextToolbar] for one, and a desktop has none of its own. Show it with
 * [TouchTextToolbarHost], and hand it to text through `LocalTextToolbar`.
 *
 * [shouldShow] is asked when a selection first wants the bar, so a platform can keep it to touch
 * and leave the mouse with its right-click menu.
 */
class TouchTextToolbar(private val shouldShow: () -> Boolean = { true }) : TextToolbar {
    /**
     * What the bar shows: where, and which buttons. Only this is state. A selection asks for the
     * bar again on every update with fresh callbacks, so keeping those in state recomposed and
     * re-placed the bar on every ask, and it flickered; an ask that changes nothing visible now
     * changes nothing.
     */
    internal var shown by mutableStateOf<Shown?>(null)
        private set

    /** The latest callbacks for the buttons [shown] offers. */
    private var handlers: Map<Action, () -> Unit> = emptyMap()

    override val status: TextToolbarStatus
        get() = if (shown != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // Compose asks again on every selection or layout update. Whether a finger is driving is a
        // question for opening the bar, not for keeping it: asked each time, a reply streaming in
        // three seconds later took the bar away.
        if (shown == null && !shouldShow()) return
        handlers = buildMap {
            onCutRequested?.let { put(Action.CUT, it) }
            onCopyRequested?.let { put(Action.COPY, it) }
            onPasteRequested?.let { put(Action.PASTE, it) }
            onSelectAllRequested?.let { put(Action.SELECT_ALL, it) }
        }
        val next = Shown(rect, Action.entries.filter { it in handlers })
        if (next != shown) shown = next
    }

    override fun hide() {
        handlers = emptyMap()
        if (shown != null) shown = null
    }

    /** Runs [action]'s latest callback. */
    internal fun perform(action: Action) {
        handlers[action]?.invoke()
    }

    /** The bar over [rect] with [offers], in the order phones put them. */
    internal data class Shown(val rect: Rect, val offers: List<Action>)

    internal enum class Action(val label: String) {
        CUT("Cut"),
        COPY("Copy"),
        PASTE("Paste"),
        SELECT_ALL("Select all"),
    }
}

/**
 * Where "Quote" on the selection bar sends the selected text: the chat prompt on screen, which
 * registers itself as [target] while it is shown. No target, no Quote.
 */
class QuoteSink {
    var target by mutableStateOf<((String) -> Unit)?>(null)
}

/** The window's [QuoteSink], for the chat prompt to register with. */
val LocalQuoteSink = staticCompositionLocalOf<QuoteSink?> { null }

/**
 * [prompt] with [quoted] added as a Markdown quote, each line marked, with the caret's room below
 * it. What is already in the prompt stays ahead of the quote.
 */
fun quoteIntoPrompt(prompt: String, quoted: String): String {
    val text = quoted.trim()
    if (text.isEmpty()) return prompt
    val quote = text.lines().joinToString("\n") { line -> if (line.isBlank()) ">" else "> $line" }
    val before = prompt.trimEnd()
    return if (before.isEmpty()) "$quote\n\n" else "$before\n\n$quote\n\n"
}

/**
 * Draws [toolbar] above the selection it was asked for, or below it when there is no room above.
 * Composed once at the root of the window, since the selection's rectangle is in root space.
 * With a [quoteSink] that has a target, it also offers Quote.
 */
@Composable
fun TouchTextToolbarHost(toolbar: TouchTextToolbar, quoteSink: QuoteSink? = null) {
    val shown = toolbar.shown ?: return
    @Suppress("DEPRECATION") // The selection bar reads and restores text only; the suspend Clipboard adds nothing here.
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val quoteTarget = quoteSink?.target
    val quote: (() -> Unit)? = if (TouchTextToolbar.Action.COPY in shown.offers && quoteTarget != null) {
        {
            // Compose hands the bar a copy, not the text. Copy, read it, and put back whatever the
            // clipboard held before, so quoting does not cost the user their clipboard.
            val previous = clipboard.getText()
            toolbar.perform(TouchTextToolbar.Action.COPY)
            val selected = clipboard.getText()?.text
            if (previous != null) clipboard.setText(previous)
            if (!selected.isNullOrBlank()) quoteTarget(selected)
        }
    } else {
        null
    }
    val actions = shown.offers.map { action -> action.label to { toolbar.perform(action) } } +
        listOfNotNull(quote?.let { "Quote" to it })
    if (actions.isEmpty()) return
    val gap = with(LocalDensity.current) { TOOLBAR_GAP.roundToPx() }
    val position = remember(shown.rect, gap) { AboveSelection(shown.rect, gap) }
    Popup(popupPositionProvider = position) {
        Surface(
            shape = RoundedCornerShape(TOOLBAR_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { index, (label, action) ->
                    if (index > 0) VerticalDivider(modifier = Modifier.heightIn(max = BUTTON_HEIGHT / 2))
                    TextButton(
                        onClick = {
                            action()
                            toolbar.hide()
                        },
                        modifier = Modifier.heightIn(min = BUTTON_HEIGHT).widthIn(min = BUTTON_MIN_WIDTH),
                    ) {
                        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }
    }
}

/** Centred over [selection] (root space), clear of it by [gap]; below it when the top is out of room. */
private class AboveSelection(private val selection: Rect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centreX = anchorBounds.left + selection.center.x.roundToInt()
        val x = (centreX - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top + selection.top.roundToInt() - gap - popupContentSize.height
        val below = anchorBounds.top + selection.bottom.roundToInt() + gap
        val y = if (above >= 0) above else below.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

private val BUTTON_HEIGHT = 48.dp
private val BUTTON_MIN_WIDTH = 64.dp
private val TOOLBAR_CORNER = 24.dp
private val TOOLBAR_GAP = 12.dp
