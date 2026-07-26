package com.letta.mobile.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.awt.Frame

internal enum class DesktopWindowChrome {
    JewelDecorated,
    JewelSystemDecorated,
}

@Composable
internal fun DesktopJewelWindow(
    title: String,
    state: WindowState,
    onCloseRequest: () -> Unit,
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
 * Custom dark title bar matching the Penpot desktop mockup (44.dp, #121212):
 * an app menu on the left, the centered conversation title, and window
 * controls on the right. Rendered for every runtime (decorated Jewel chrome
 * only works under the JetBrains Runtime), so the window is undecorated and we
 * draw + drive the chrome ourselves.
 */
@Composable
private fun DesktopWindowTitleBar(
    windowScope: WindowScope,
    title: String,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        with(windowScope) {
            WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left third: app menu.
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = "File     Edit     View     Window",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    // Center: conversation title.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
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
                    // Right third: window controls.
                    Row(
                        modifier = Modifier.weight(1f),
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun WindowControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    hoverDestructive: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (hoverDestructive) {
                MaterialTheme.colorScheme.onSurfaceVariant
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
