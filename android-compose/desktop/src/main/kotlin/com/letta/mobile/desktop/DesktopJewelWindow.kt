package com.letta.mobile.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import java.awt.Frame

internal enum class DesktopWindowChrome {
    JewelDecorated,
    JewelSystemDecorated,
}

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
 * chat/shell state) into the window's custom title bar, mirroring the
 * existing `windowTitle`/`onActiveTitleChange` lift in Main.kt. Carries both
 * the agent-first identity block (letta-mobile-3arhe.1) and the
 * collapsible-sidebar toggle (letta-mobile-o5m90) — the toggle needs to live
 * in the always-visible chrome, not floating over content, to stay
 * discoverable once the sidebar is hidden.
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

@Composable
internal fun DesktopJewelWindow(
    title: String,
    state: WindowState,
    onCloseRequest: () -> Unit,
    header: DesktopHeaderChromeState = DesktopHeaderChromeState.Empty,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    DesktopJewelTheme {
        Window(
            onCloseRequest = onCloseRequest,
            title = title,
            state = state,
            undecorated = true,
        ) {
            val frameScope: WindowScope = this
            val composeWindow = window
            Column(modifier = Modifier.fillMaxSize()) {
                DesktopMaterialTheme {
                    DesktopWindowTitleBar(
                        windowScope = frameScope,
                        title = title,
                        header = header,
                        onMinimize = { composeWindow.extendedState = Frame.ICONIFIED },
                        onToggleMaximize = {
                            state.placement = if (state.placement == WindowPlacement.Maximized) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                        },
                        onClose = onCloseRequest,
                    )
                }
                content()
            }
        }
    }
}

/**
 * Custom dark title bar: sidebar toggle, then the agent-first identity block
 * (agent orb, conversation title over agent name) on the left, window
 * controls on the right. Rendered for every runtime (decorated Jewel chrome
 * only works under the JetBrains Runtime), so the window is undecorated and we
 * draw + drive the chrome ourselves.
 */
@Composable
internal fun DesktopWindowTitleBar(
    windowScope: WindowScope,
    title: String,
    header: DesktopHeaderChromeState,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        with(windowScope) {
            WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
                // Surface (not a plain `.background()` modifier) so it
                // provides `LocalContentColor` = onSurface for this
                // container — the root fix for header icons/text silently
                // resolving to the Compose default (black) instead of a
                // theme-aware tone: any Icon/Text here that doesn't set an
                // explicit color now still lands on a color that is legible
                // in both themes, and callers should still prefer an
                // explicit semantic tint over relying on this default.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TitleBarHeight),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Sidebar toggle: the one control that survives collapse
                    // (o5m90 AC #1) — lives in the always-visible chrome so it
                    // stays discoverable once the sidebar is hidden, unlike a
                    // control floating over content.
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
                    // Agent-first identity: leading avatar, conversation title
                    // over agent name (letta-mobile-3arhe.1). Falls back to a
                    // bare title when there is no pinned conversation yet.
                    // Bounded by a fixed max width (not a Row `weight`) — see
                    // [IdentityBlockMaxWidth] for why a weighted sibling here
                    // broke the caption buttons' flush-right position.
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
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Flexible drag region: the ONLY weighted element in this
                    // row, so it always absorbs exactly the leftover width —
                    // guaranteeing the trailing controls and caption buttons
                    // land flush against the window's right edge regardless
                    // of how wide the identity block actually rendered.
                    Box(modifier = Modifier.weight(1f).fillMaxHeight())
                    if (identity != null) {
                        DesktopHeaderTrailingControls(state = identity, actions = header.identityActions)
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WindowControlButton(Icons.Outlined.Remove, "Minimize", onMinimize)
                        WindowControlButton(Icons.Outlined.CropSquare, "Maximize", onToggleMaximize)
                        WindowControlButton(
                            icon = Icons.Outlined.Close,
                            description = "Close",
                            onClick = onClose,
                            hoverDestructive = true,
                        )
                    }
                }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** Windows 11 close-button hover red. */
private val WindowsCloseHoverRed = Color(0xFFC42B1C)

@Composable
private fun WindowControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    hoverDestructive: Boolean = false,
) {
    // Standard Windows caption-button hover: subtle wash for min/max, the
    // system red with a white glyph for close.
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        hovered && hoverDestructive -> WindowsCloseHoverRed
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 44.dp)
            .hoverable(interactionSource)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (hovered && hoverDestructive) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(15.dp),
        )
    }
}

internal fun selectDesktopWindowChrome(
    osName: String = System.getProperty("os.name").orEmpty(),
    isJetBrainsRuntimeAvailable: Boolean = isJetBrainsRuntimeAvailable(),
): DesktopWindowChrome =
    if (osName.startsWith("Windows", ignoreCase = true) && isJetBrainsRuntimeAvailable) {
        DesktopWindowChrome.JewelDecorated
    } else {
        DesktopWindowChrome.JewelSystemDecorated
    }

private fun isJetBrainsRuntimeAvailable(): Boolean =
    runCatching {
        val jbrClass = Class.forName("com.jetbrains.JBR")
        jbrClass.getMethod("isAvailable").invoke(null) as? Boolean ?: false
    }.getOrDefault(false)
