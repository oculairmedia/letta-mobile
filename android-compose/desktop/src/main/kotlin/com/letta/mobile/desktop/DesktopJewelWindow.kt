package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.AwtDecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindow
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.styling.DecoratedWindowColors
import dev.nucleusframework.window.styling.DecoratedWindowMetrics
import dev.nucleusframework.window.styling.DecoratedWindowStyle
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle

/**
 * Main desktop window chrome (letta-mobile-scedm).
 *
 * Previously this used a plain `androidx.compose.ui.window.Window` with
 * `undecorated = true` plus a hand-drawn title bar, because decorated Jewel
 * chrome only works under the JetBrains Runtime and this app ships on
 * Temurin. That left the OS with no real window frame at all: the only
 * native touch was [DesktopWindowsChrome] calling `DwmSetWindowAttribute`
 * for rounded corners. Aero Snap, Win+Arrow snapping, the Windows 11 Snap
 * Layouts flyout, DWM minimize/restore/maximize animations, and the standard
 * drop shadow all require a real `WS_CAPTION`/`WS_THICKFRAME` frame — an
 * undecorated window never gets any of it.
 *
 * [DecoratedWindow] is Nucleus's non-JBR decorated-window implementation
 * (`nucleus.decorated-window-core` / `-awt` / `-jni`, already on the
 * classpath for other Nucleus integrations). On Windows its JNI backend
 * (`nucleus_windows_decoration.dll`, loaded via
 * `dev.nucleusframework.window.utils.windows.JniWindowsDecorationBridge`)
 * subclasses the window's `WndProc` and installs a real native frame — the
 * standard `WM_NCCALCSIZE`/`WM_NCHITTEST` technique for keeping OS chrome
 * behavior (snap, DWM animations, shadow, rounded corners) while still
 * painting custom content into the title bar area — instead of leaving the
 * window fully undecorated. This is the maintained framework API for that
 * technique; see the letta-mobile-scedm PR description for why hand-rolling
 * `SetWindowLongPtr(GWLP_WNDPROC)` ourselves via JNA was not necessary.
 *
 * If the native library fails to load (or on Linux, where the JNI backend
 * only ships a Wayland/X11 helper without full native decoration) Nucleus
 * transparently falls back to an undecorated window with Compose-driven drag
 * and double-click-to-maximize — degraded but not broken, and still
 * Windows-guarded internally by Nucleus itself.
 */
@Composable
internal fun DesktopJewelWindow(
    title: String,
    state: WindowState,
    onCloseRequest: () -> Unit,
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
                        metrics = TitleBarMetrics(height = 44.dp),
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
                    // Custom-drawn title content (icon + conversation title),
                    // matching the Penpot desktop mockup. The drag region,
                    // window control buttons (minimize/maximize/close), and
                    // double-click-to-maximize are all supplied by [TitleBar]
                    // itself — on Windows, dragging goes through
                    // JniWindowsDecorationBridge.nativeStartDrag, which is
                    // what restores Aero Snap. The maximize button here is
                    // still a Compose-drawn client-area button, not a native
                    // HTMAXBUTTON hit-test region, so the Windows 11 Snap
                    // Layouts hover flyout is NOT reproduced by this button —
                    // see the letta-mobile-scedm PR notes for that gap.
                    // We only provide the centered label content below.
                    TitleBar(style = titleBarStyle) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
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
                }
            }
            content()
        }
    }
}
