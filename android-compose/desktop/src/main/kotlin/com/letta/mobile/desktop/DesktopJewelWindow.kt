package com.letta.mobile.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import com.letta.mobile.ui.canvas.LocalCanvasPenTarget
import com.letta.mobile.ui.components.LocalMenuActionScope
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.host.desktopPointer
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeader
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.barHeight
import com.letta.mobile.ui.theme.LettaDimens
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

/** Title-bar height: taller than a stock 32-44dp caption bar to comfortably
 * fit the two-line agent identity block (title over agent name). */
private val TitleBarHeight = 48.dp

/** Text-selection handles at twice the mouse-sized default, so a fingertip can take hold of them. */
private const val TOUCH_HANDLE_SCALE = 2f

/**
 * Native desktop frame with the shared header in Nucleus's title bar. Nucleus supplies window
 * controls, snapping and native dragging; Main binds close-to-tray through [onCloseRequest].
 * [header] stays a state holder here so a title-only update cannot restart window content.
 */
@Composable
internal fun DesktopJewelWindow(
    title: String,
    state: WindowState,
    onCloseRequest: () -> Unit,
    header: State<ShellWorkspaceHeaderState?>,
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
                val titleBarStyle = rememberDesktopTitleBarStyle(header)
                val windowStyle = remember(colorScheme) { decoratedWindowStyle(colorScheme) }
                NucleusDecoratedWindowTheme(
                    isDark = dark,
                    windowStyle = windowStyle,
                    titleBarStyle = titleBarStyle,
                ) {
                    // letta-mobile-ckmub.3.1: panes, tooltips and row menus as a pointer window draws them.
                    // The title bar gets the host too, so the header search's pane opens as a pointer popover.
                    val lettaHost = remember { LettaHost.desktopPointer(::hoverTimeLabel) }
                    BasicTitleBar(
                        style = titleBarStyle,
                        layoutPolicy = TitleBarLayoutPolicy.FillCenter,
                    ) {
                        CompositionLocalProvider(LocalLettaHost provides lettaHost) {
                            DesktopTitleBarHeader(
                                title = title,
                                header = header,
                                modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.sm),
                            )
                        }
                    }
                    // The pen belongs to this window (two windows cannot take each other's strokes).
                    // The window's own scope runs what a menu item chose: a popup is dismissed by
                    // being removed, so the action cannot belong to the popup.
                    val windowScope = rememberCoroutineScope()
                    // Text selected by touch gets finger-sized handles and a Copy / Quote bar;
                    // the mouse keeps its own, and the bar only shows just after a finger.
                    val touchToolbar = remember {
                        com.letta.mobile.ui.text.TouchTextToolbar {
                            com.letta.mobile.desktop.touch.DesktopTouchOrigin.wasTouch(System.currentTimeMillis())
                        }
                    }
                    val quoteSink = remember { com.letta.mobile.ui.text.QuoteSink() }
                    CompositionLocalProvider(
                        com.letta.mobile.ui.text.LocalSelectionHandleScale provides TOUCH_HANDLE_SCALE,
                        androidx.compose.ui.platform.LocalTextToolbar provides touchToolbar,
                        com.letta.mobile.ui.text.LocalQuoteSink provides quoteSink,
                        LocalCanvasPenTarget provides com.letta.mobile.desktop.input.WindowPenTarget(window),
                        com.letta.mobile.ui.canvas.LocalCanvasPenRegistry provides penRegistry,
                        LocalMenuActionScope provides windowScope,
                        LocalLettaHost provides lettaHost,
                    ) {
                        content()
                        com.letta.mobile.ui.text.TouchTextToolbarHost(touchToolbar, quoteSink)
                    }
                }
            }
        }
    }
}

/** Always leaves an unobstructed title-bar lane for native window dragging. */
private val MinimumTitleBarDragWidth = 96.dp

/**
 * The shared workspace header in the title bar (letta-mobile-ckmub.17.4): the workspace hoists it
 * here because the title bar composes as a sibling of the content. The free width the header leaves
 * stays the native drag lane; with tabs the strip reserves that lane itself ([MinimumTitleBarDragWidth]).
 */
@Composable
internal fun DesktopTitleBarHeader(title: String, header: State<ShellWorkspaceHeaderState?>, modifier: Modifier) {
    ShellWorkspaceHeader(
        state = header.value ?: ShellWorkspaceHeaderState(title = title),
        modifier = modifier,
        dragLaneWidth = MinimumTitleBarDragWidth,
    )
}

/** Only geometry changes escape the title bar's observation scope. */
@Composable
internal fun rememberDesktopTitleBarStyle(header: State<ShellWorkspaceHeaderState?>): TitleBarStyle {
    val colors = MaterialTheme.colorScheme
    val geometry by remember(header) {
        derivedStateOf(structuralEqualityPolicy()) {
            val current = header.value
            // Tabs join the page without a hairline; compact windows use the shared phone height.
            (current?.tabs?.tabs?.isNotEmpty() == true) to (current?.barHeight(TitleBarHeight) ?: TitleBarHeight)
        }
    }
    return remember(colors, geometry) { titleBarStyle(colors, geometry.first, geometry.second) }
}

private fun titleBarStyle(colorScheme: ColorScheme, tabbed: Boolean, height: Dp) = TitleBarStyle(
    colors = TitleBarColors(
        background = colorScheme.surfaceContainerLow,
        inactiveBackground = colorScheme.surfaceContainerLow,
        content = colorScheme.onSurface,
        border = if (tabbed) Color.Transparent else colorScheme.outlineVariant,
    ),
    metrics = TitleBarMetrics(height = height),
)

private fun decoratedWindowStyle(colorScheme: ColorScheme) = DecoratedWindowStyle(
    colors = DecoratedWindowColors(
        border = colorScheme.outlineVariant,
        borderInactive = colorScheme.outlineVariant,
        background = colorScheme.surfaceContainerLow,
    ),
    metrics = DecoratedWindowMetrics(borderWidth = 1.dp),
)