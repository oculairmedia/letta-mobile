package com.letta.mobile.ui.shell.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The one page frame every shared page renders in (hard cut P2,). It owns the
 * window's system-bar, display-cutout and keyboard insets exactly once, so the page underneath never
 * pads for them itself (no `statusBarsPadding`/`navigationBarsPadding`/`systemBarsPadding`/
 * `imePadding` in a page; `PageInsetModifierGuardTest` holds that line).
 *
 * The page's [containerColor] draws edge to edge, behind the status and navigation bars (Android runs
 * every Activity with `enableEdgeToEdge`); the insets are split across the slots:
 * - [header] sits below the status bar / cutout (top and sides) at its own height: it is measured
 *   without a height bound, so it never takes the content's space;
 * - [bottomBar] (composer, actions) sits above the navigation bar and, when [imeAware], the keyboard;
 * - [content] receives the remaining insets as [PaddingValues] — the top when there is no header, the
 *   bottom when there is no bottom bar, and the sides — and applies them as padding or as a list's
 *   content padding so it can scroll behind the bars;
 * - [floatingAction] floats at the content area's bottom end, clear of the same insets.
 *
 * Whatever an ancestor already consumed (a navigation rail's side insets, an enclosing scaffold) is
 * not applied again, and everything this frame applies is consumed for its descendants.
 *
 * On desktop and web the window reports no system-bar or keyboard insets, so the frame is a plain
 * header / content / bottom-bar column there with the same slots.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LettaPageScaffold(
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    floatingAction: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    insets: WindowInsets = LettaPageScaffoldDefaults.insets,
    imeInsets: WindowInsets = WindowInsets.ime,
    imeAware: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    var consumedByAncestors by remember { mutableStateOf(WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)) }
    val pageInsets = (if (imeAware) insets.union(imeInsets) else insets).exclude(consumedByAncestors)
    val split = splitPageInsets(
        frame = pageInsets.asPaddingValues(),
        hasHeader = header != null,
        hasBottomBar = bottomBar != null,
        layoutDirection = LocalLayoutDirection.current,
    )
    Box(
        modifier
            .onConsumedWindowInsetsChanged { consumedByAncestors = it }
            .fillMaxSize()
            .background(containerColor)
            .consumeWindowInsets(pageInsets),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (header != null) {
                // The header wraps its own height: it is measured without the page's height, so a
                // header that fills the height it is offered (ShellHeader's free-width lane does, to
                // span desktop's title bar) collapses to its content instead of taking the whole page
                // and leaving the content slot nothing (letta-mobile-kg4vy).
                Box(Modifier.fillMaxWidth().padding(split.header).wrapContentHeight(Alignment.Top, unbounded = true)) {
                    header()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                content(split.content)
                if (floatingAction != null) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(split.floatingAction)) { floatingAction() }
                }
            }
            if (bottomBar != null) {
                Box(Modifier.fillMaxWidth().padding(split.bottomBar)) { bottomBar() }
            }
        }
    }
}

/** Defaults for [LettaPageScaffold]. */
object LettaPageScaffoldDefaults {
    /**
     * The system bars and the display cutout, without the keyboard (the scaffold adds that when
     * `imeAware`). Zero on desktop and web, whose windows report no such insets.
     */
    val insets: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

/**
 * How [LettaPageScaffold] shares one set of window insets between its slots: each side's inset goes
 * to exactly one slot along that axis, so nothing is padded twice and nothing is left unpadded.
 */
@Immutable
internal data class PageInsetSplit(
    val header: PaddingValues,
    val content: PaddingValues,
    val bottomBar: PaddingValues,
    val floatingAction: PaddingValues,
)

internal fun splitPageInsets(
    frame: PaddingValues,
    hasHeader: Boolean,
    hasBottomBar: Boolean,
    layoutDirection: LayoutDirection,
): PageInsetSplit {
    val start = frame.calculateStartPadding(layoutDirection)
    val end = frame.calculateEndPadding(layoutDirection)
    val top = frame.calculateTopPadding()
    val bottom = frame.calculateBottomPadding()
    val contentTop = if (hasHeader) 0.dp else top
    val contentBottom = if (hasBottomBar) 0.dp else bottom
    return PageInsetSplit(
        header = PaddingValues(start = start, top = top, end = end),
        content = PaddingValues(start = start, top = contentTop, end = end, bottom = contentBottom),
        bottomBar = PaddingValues(start = start, end = end, bottom = bottom),
        floatingAction = PaddingValues(end = end, bottom = contentBottom),
    )
}
