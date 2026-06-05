package com.letta.mobile.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Letta Desktop",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(960, 640)
        }

        LettaDesktopApp()
    }
}
