package com.letta.mobile.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import org.jetbrains.jewel.intui.standalone.window.Window
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar

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
    val chrome = remember { selectDesktopWindowChrome() }

    DesktopJewelTheme {
        when (chrome) {
            DesktopWindowChrome.JewelDecorated -> {
                DecoratedWindow(
                    onCloseRequest = onCloseRequest,
                    title = title,
                    state = state,
                ) {
                    TitleBar {
                        JewelText(
                            text = title,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(start = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    content()
                }
            }
            DesktopWindowChrome.JewelSystemDecorated -> {
                Window(
                    onCloseRequest = onCloseRequest,
                    title = title,
                    state = state,
                    content = content,
                )
            }
        }
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
