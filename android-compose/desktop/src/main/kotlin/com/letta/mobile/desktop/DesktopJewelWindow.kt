package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
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

/** Overflow entry points surfaced next to the sidebar toggle while the
 * sidebar is collapsed — see [DesktopSidebarOverflowMenu]. */
@Immutable
internal data class DesktopHeaderSidebarOverflow(
    val mode: WorkPlayMode,
    val onNewChat: () -> Unit,
    val onDestination: (LensDestination) -> Unit,
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
            DesktopMaterialTheme {
                val colorScheme = MaterialTheme.colorScheme
                val titleBarStyle = remember(colorScheme) {
                    TitleBarStyle(
                        colors = TitleBarColors(
                            background = colorScheme.surfaceContainerLow,
                            inactiveBackground = colorScheme.surfaceContainerLow,
                            content = colorScheme.onSurface,
                            border = colorScheme.outlineVariant,
                        ),
                        metrics = TitleBarMetrics(height = TitleBarHeight),
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
                    BasicTitleBar(
                        style = titleBarStyle,
                        layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp),
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
                                    )
                                }
                            }
                            Box(modifier = Modifier.width(4.dp))
                            // Agent-first identity: leading avatar, conversation
                            // title over agent name (letta-mobile-3arhe.1).
                            val identity = header.identity
                            if (identity != null) {
                                DesktopHeaderIdentityBlock(
                                    state = identity,
                                    actions = header.identityActions,
                                    modifier = Modifier.widthIn(max = IdentityBlockMaxWidth),
                                )
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .widthIn(max = IdentityBlockMaxWidth)
                                        .padding(horizontal = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = colorScheme.onSurface,
                                        modifier = Modifier.size(15.dp),
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
                            // Flexible drag region: the ONLY weighted element
                            // in this row, so it always absorbs exactly the
                            // leftover width — guaranteeing Nucleus's caption
                            // buttons land flush against the window's right
                            // edge regardless of how wide the identity block
                            // actually rendered.
                            Box(modifier = Modifier.weight(1f).fillMaxHeight())
                            if (identity != null) {
                                DesktopHeaderTrailingControls(state = identity, actions = header.identityActions)
                            }
                        }
                    }
                    content()
                }
            }
        }
    }
}
