package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.canvas.LocalCanvasPenTarget
import com.letta.mobile.ui.components.LocalMenuActionScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.touch.DesktopTouchDragExclusion
import com.letta.mobile.desktop.touch.screenExclusionRectOrNull
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.AwtDecoratedWindowScope
import dev.nucleusframework.window.BasicTitleBar
import dev.nucleusframework.window.DecoratedWindow
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.styling.DecoratedWindowColors
import dev.nucleusframework.window.styling.DecoratedWindowMetrics
import dev.nucleusframework.window.styling.DecoratedWindowStyle
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle
import java.awt.Rectangle
import com.letta.mobile.ui.theme.LettaDimens

/** Overflow entry points surfaced next to the sidebar toggle while the
 * sidebar is collapsed — see [DesktopSidebarOverflowMenu]. */
@Immutable
/**
 * The header's unified search: a persistent field, with its results in a panel
 * under it. Not a chevron that opens a menu — the field is always there and is
 * typed into directly.
 *
 * [config] carries the scopes, the "Filter to this agent" toggle and the
 * placeholder, so the window only has to draw it.
 */
internal data class DesktopHeaderSearch(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val sections: List<com.letta.mobile.ui.search.LettaSearchSection>,
    val onRowSelected: (com.letta.mobile.ui.search.LettaSearchRow) -> Unit,
    val onDismiss: () -> Unit,
    val config: com.letta.mobile.ui.search.LettaSearchConfig,
)

internal data class DesktopHeaderSidebarOverflow(
    val mode: WorkPlayMode,
    val onNewChat: () -> Unit,
    val onDestination: (LensDestination) -> Unit,
    val onSettings: () -> Unit,
)

/**
 * Header chrome state lifted out of [LettaDesktopApp] (which owns the live
 * chat/shell state) into the window's title bar, mirroring the existing
 * `windowTitle`/`onActiveTitleChange` lift in Main.kt. Carries both the
 * agent-first identity block (letta-mobile-3arhe.1) and the collapsible-sidebar
 * toggle (letta-mobile-o5m90) — the toggle needs to live in the
 * always-visible chrome, not floating over content, to stay discoverable once
 * the sidebar is hidden.
 */
@Immutable
internal data class DesktopHeaderChromeState(
    val identity: NowActiveBarState?,
    val identityActions: NowActiveBarActions,
    val sidebarCollapsed: Boolean,
    val onToggleSidebar: () -> Unit,
    val sidebarToggleFocusRequester: FocusRequester? = null,
    val sidebarOverflow: DesktopHeaderSidebarOverflow? = null,
    val search: DesktopHeaderSearch? = null,
    val conversationTabs: List<DesktopConversationTab> = emptyList(),
    val activeConversationId: String? = null,
    val onSelectConversationTab: (String) -> Unit = {},
    val onCloseConversationTab: (String) -> Unit = {},
    val onReorderConversationTab: (conversationId: String, targetIndex: Int) -> Unit = { _, _ -> },
    val onNewConversationTab: (() -> Unit)? = null,
) {
    companion object {
        val Empty = DesktopHeaderChromeState(
            identity = null,
            identityActions = NowActiveBarActions(onOpenConversation = {}, onJumpToBackgroundWork = {}),
            sidebarCollapsed = true,
            onToggleSidebar = {},
        )
    }
}

/** Title-bar height: taller than a stock 32-44dp caption bar to comfortably
 * fit the two-line agent identity block (title over agent name). */
private val TitleBarHeight = 48.dp

/** With tabs the strip is one line per tab (title, then agent), so the bar can be browser-thin. */
private val TabbedTitleBarHeight = LettaDimens.Control.actionButton

/**
 * Bounds the identity block by a fixed max width rather than a Row `weight`.
 * A `weight(fill = false)` sibling next to a `weight(fill = true)` drag
 * spacer leaves the shortfall between the identity block's actual (smaller)
 * width and its allotted weighted share as trailing dead space at the END of
 * the row — after the window control buttons — instead of being reclaimed by
 * the spacer. A fixed `widthIn(max = …)` sizes the block to its own content
 * (so short titles don't take excess room) while still bounding it for
 * ellipsis on long titles, and leaves the single flexible spacer as the only
 * element that absorbs leftover width, which keeps the caption buttons flush
 * to the window's right edge at any width.
 */
private val IdentityBlockMaxWidth = 320.dp
private val HeaderSearchWidth = 260.dp

/** Always leaves an unobstructed title-bar lane for native window dragging. */
private val MinimumTitleBarDragWidth = 96.dp

/**
 * Main desktop window chrome.
 *
 * Uses Nucleus's `DecoratedWindow` to get a real native Windows frame —
 * Aero Snap, Win+Arrow snapping, DWM minimize/restore/maximize animations,
 * the standard drop shadow, and rounded corners — while still painting
 * custom content (sidebar toggle + agent identity block) into the title bar
 * area. The window control buttons (minimize/maximize/close) are supplied
 * by Nucleus's `TitleBar`; the close button calls `onCloseRequest`, which
 * Main.kt wires to `activationHandler.hideWindow()` for close-to-tray.
 *
 * Previously this used a plain `androidx.compose.ui.window.Window` with
 * `undecorated = true` plus a hand-drawn title bar, because decorated Jewel
 * chrome only works under the JetBrains Runtime and this app ships on
 * Temurin. That left the OS with no real window frame at all.
 */
@Composable
internal fun DesktopJewelWindow(
    title: String,
    state: WindowState,
    onCloseRequest: () -> Unit,
    header: DesktopHeaderChromeState = DesktopHeaderChromeState.Empty,
    content: @Composable AwtDecoratedWindowScope.() -> Unit,
) {
    DesktopJewelTheme {
        val dark = isSystemInDarkMode()
        DecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            title = title,
        ) {
            // The pen, read from Windows Ink and posted as ordinary mouse input, so it can draw
            // AND press things. AWT reports no stylus of its own — measured, see
            // letta-mobile-4i2z9.5 — so without this the tablet does nothing at all.
            //
            // The registry is this window's: the pen delivers into it and the canvas composed
            // below registers with it, so the table of live consumers dies with the window that
            // owns it rather than outliving every window in the process.
            val penRegistry = remember(window) { com.letta.mobile.ui.canvas.CanvasPenRegistry() }
            com.letta.mobile.desktop.input.InstallTabletPen(window, penRegistry)
            DesktopMaterialTheme {
                val colorScheme = MaterialTheme.colorScheme
                // With a tab strip the active tab is painted in the page
                // background and must run straight into the content beneath it,
                // so the title bar's hairline is dropped — the tab-vs-strip
                // contrast carries the separation instead (browser behaviour).
                val tabbed = header.conversationTabs.isNotEmpty()
                val titleBarStyle = remember(colorScheme, tabbed) {
                    TitleBarStyle(
                        colors = TitleBarColors(
                            background = colorScheme.surfaceContainerLow,
                            inactiveBackground = colorScheme.surfaceContainerLow,
                            content = colorScheme.onSurface,
                            border = if (tabbed) Color.Transparent else colorScheme.outlineVariant,
                        ),
                        metrics = TitleBarMetrics(height = if (tabbed) TabbedTitleBarHeight else TitleBarHeight),
                    )
                }
                val windowStyle = remember(colorScheme) {
                    DecoratedWindowStyle(
                        colors = DecoratedWindowColors(
                            border = colorScheme.outlineVariant,
                            borderInactive = colorScheme.outlineVariant,
                            background = colorScheme.surfaceContainerLow,
                        ),
                        metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
                    )
                }
                NucleusDecoratedWindowTheme(
                    isDark = dark,
                    windowStyle = windowStyle,
                    titleBarStyle = titleBarStyle,
                ) {
                    // Windows touchscreens: DesktopWindowsTouchInput swallows every
                    // touch drag and replays it as wheel-scroll, which would eat the
                    // title bar's own drag-to-move gesture before Nucleus ever sees
                    // it. Publishing these bounds tells the shim to leave gestures
                    // that start here alone (letta-mobile touch-title-bar regression).
                    // Screen coordinates sidestep both the density scaling between
                    // Compose's px space and AWT's, and any offset between the AWT
                    // component that receives the touch event and this content.
                    //
                    // X and width are measured from this Row -- our own content --
                    // rather than from BasicTitleBar's own outer modifier, which was
                    // tried first and reported a rectangle offset downward by exactly
                    // one title bar height from where the title bar and tabs actually
                    // render on screen.
                    //
                    // Y is deliberately NOT taken from this same measurement, even
                    // though it lives in the same LayoutCoordinates: live probing
                    // showed this Row's own onGloballyPositioned firing with two
                    // different Y values across layout passes in the same run --
                    // window-relative Y=0 on some passes, Y=TitleBarHeight (one full
                    // title-bar height) on others -- while X and width stayed put.
                    // That is consistent with Nucleus's native title-bar-height
                    // plumbing (TitleBarLayoutPolicy's applyTitleBar callback and the
                    // JNI nativeSetTitleBarHeight path) intermittently double-counting
                    // its own inset when it recomputes the client-area offset, not
                    // with anything this file controls -- so there is no "correct"
                    // Y to read from this coordinate space at all, only two different
                    // wrong-some-of-the-time ones. The title bar is always the top
                    // TitleBarHeight of the window, full stop, so anchoring Y to the
                    // window's own (stable) screen position sidesteps the flip
                    // entirely instead of trying to pick the right transient sample.
                    DisposableEffect(window) {
                        onDispose { DesktopTouchDragExclusion.publish(window, null) }
                    }
                    val titleBarHeightPx = with(LocalDensity.current) { TitleBarHeight.roundToPx() }
                    BasicTitleBar(
                        style = titleBarStyle,
                        layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = LettaDimens.Space.sm)
                                .onGloballyPositioned { coordinates ->
                                    val bounds = titleBarScreenBoundsOrNull(
                                        coordinates = coordinates,
                                        windowOriginOnScreen = runCatching { window.locationOnScreen }.getOrNull(),
                                        titleBarHeightPx = titleBarHeightPx,
                                    )
                                    DesktopTouchDragExclusion.publish(window, bounds)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Sidebar toggle: the one control that survives
                            // collapse (o5m90 AC #1) — lives in the
                            // always-visible chrome so it stays discoverable
                            // once the sidebar is hidden.
                            DesktopSidebarToggleButton(
                                collapsed = header.sidebarCollapsed,
                                onToggle = header.onToggleSidebar,
                                focusRequester = header.sidebarToggleFocusRequester,
                            )
                            header.sidebarOverflow?.let { overflow ->
                                if (header.sidebarCollapsed) {
                                    DesktopSidebarOverflowMenu(
                                        mode = overflow.mode,
                                        onNewChat = overflow.onNewChat,
                                        onDestination = overflow.onDestination,
                                        onSettings = overflow.onSettings,
                                    )
                                }
                            }
                            Box(modifier = Modifier.width(LettaDimens.Space.xs))
                            header.search?.let { search ->
                                com.letta.mobile.ui.search.LettaSearchAnchoredField(
                                    query = search.query,
                                    onQueryChange = search.onQueryChange,
                                    sections = search.sections,
                                    onRowSelected = search.onRowSelected,
                                    onDismiss = search.onDismiss,
                                    // Re-enable focus for this subtree. Nucleus's TitleBarCore
                                    // applies `focusProperties { canFocus = false }` to the whole
                                    // title bar on Windows/Linux, so Tab navigation cannot wander
                                    // into the window-drag area — which also means no child there
                                    // can take focus. Buttons never noticed; a text field could be
                                    // clicked and never receive a keystroke. Nucleus's own comment
                                    // notes macOS keeps focus enabled exactly so TextField children
                                    // in the title bar work, so this restores that locally rather
                                    // than moving the field out of the header.
                                    modifier = Modifier
                                        .width(HeaderSearchWidth)
                                        .focusProperties { canFocus = true },
                                    config = search.config,
                                )
                            }

                            // Agent-first identity: leading avatar, conversation
                            // title over agent name (letta-mobile-3arhe.1).
                            val identity = header.identity
                            if (header.conversationTabs.isNotEmpty()) {
                                // Keep the weighted container itself non-interactive: the tab strip
                                // fills it edge to edge, reserving the drag lane as a trailing blank
                                // item inside the LazyRow itself (see DesktopConversationTabRow's
                                // dragLaneWidth) rather than shrinking the row's own width via
                                // widthIn(max = ...). A width-constrained LazyRow left the row's
                                // measured/virtualized viewport narrower than the space a tab could
                                // be dragged into, clipping the dragged tab the moment it crossed
                                // that boundary; letting the row use the full weighted width and
                                // spending the reserved lane as real (if invisible) row content keeps
                                // every dragged position inside the row's own bounds. Blank space
                                // both inside the tab strip and past its last tab remains draggable;
                                // trailing controls stay fixed.
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                ) {
                                    DesktopConversationTabRow(
                                        tabs = header.conversationTabs,
                                        activeConversationId = header.activeConversationId,
                                        actions = DesktopConversationTabActions(
                                            onSelect = header.onSelectConversationTab,
                                            onClose = header.onCloseConversationTab,
                                            onReorder = header.onReorderConversationTab,
                                            onNewConversation = header.onNewConversationTab,
                                        ),
                                        dragLaneWidth = MinimumTitleBarDragWidth,
                                        modifier = Modifier.fillMaxHeight(),
                                    )
                                }
                            } else if (identity != null) {
                                DesktopHeaderIdentityBlock(
                                    state = identity,
                                    actions = header.identityActions,
                                    modifier = Modifier.widthIn(max = IdentityBlockMaxWidth),
                                )
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .widthIn(max = IdentityBlockMaxWidth)
                                        .padding(horizontal = LettaDimens.Space.sm),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = colorScheme.onSurface,
                                        modifier = Modifier.size(LettaDimens.Control.icon),
                                    )
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // Without tabs, this is the only weighted element. With tabs, the
                            // weighted Box above doubles as the drag region around its tab strip
                            // (a LazyRow filling the Box edge to edge). Either way, caption
                            // controls remain fixed.
                            if (header.conversationTabs.isEmpty()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                            if (identity != null) {
                                DesktopHeaderTrailingControls(state = identity, actions = header.identityActions)
                            }
                        }
                    }
                    // The pen belongs to this window: a canvas composed inside it registers
                    // under this window's identity, so two open windows cannot take each other's
                    // strokes.
                    // The window's own scope runs what a menu item chose: a popup is dismissed by
                    // being removed, so the action cannot belong to the popup.
                    val windowScope = rememberCoroutineScope()
                    CompositionLocalProvider(
                        LocalCanvasPenTarget provides com.letta.mobile.desktop.input.WindowPenTarget(window),
                        com.letta.mobile.ui.canvas.LocalCanvasPenRegistry provides penRegistry,
                        LocalMenuActionScope provides windowScope,
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * The title bar's screen-space bounds for [DesktopTouchDragExclusion], or
 * null when [coordinates] cannot yet be resolved to a screen position (see
 * [screenExclusionRectOrNull] for why that happens and why null — meaning
 * "clear any previously published bounds" — is the deliberate fail-safe
 * choice rather than a best-effort rectangle).
 *
 * X and width come from [coordinates] (Compose's own measurement of this
 * Row, screen-relative). Y comes from [windowOriginOnScreen] instead of
 * [coordinates] — see the call site's comment for why the latter is
 * unreliable for Y specifically — falling back to the coordinates' own Y
 * when the window's screen position isn't available yet (very first frames,
 * before the AWT peer exists).
 */
private fun titleBarScreenBoundsOrNull(
    coordinates: LayoutCoordinates,
    windowOriginOnScreen: java.awt.Point?,
    titleBarHeightPx: Int,
): Rectangle? {
    val topLeft = coordinates.positionOnScreen()
    val y = windowOriginOnScreen?.y?.toFloat() ?: topLeft.y
    return screenExclusionRectOrNull(topLeft.x, y, coordinates.size.width, titleBarHeightPx)
}
