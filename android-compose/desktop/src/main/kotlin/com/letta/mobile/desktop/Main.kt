package com.letta.mobile.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() {
    val activationHandler = DesktopWindowActivationHandler()
    when (val singleInstance = DesktopSingleInstance.acquire(onCommand = activationHandler::handleCommand)) {
        is DesktopSingleInstance.Secondary -> {
            if (!singleInstance.notifyPrimary()) {
                System.err.println("Letta Desktop is already running, but did not respond to the show command.")
            }
            return
        }
        is DesktopSingleInstance.Primary -> runDesktopApplication(singleInstance, activationHandler)
    }
}

private fun runDesktopApplication(
    singleInstance: DesktopSingleInstance.Primary,
    activationHandler: DesktopWindowActivationHandler,
) {
    try {
        application {
            var windowTitle by remember { mutableStateOf("Letta Desktop") }
            DesktopJewelWindow(
                onCloseRequest = ::exitApplication,
                title = windowTitle,
                state = rememberWindowState(width = 1280.dp, height = 820.dp),
            ) {
                LaunchedEffect(Unit) {
                    activationHandler.attach(window)
                    window.minimumSize = Dimension(960, 640)
                }

                LettaDesktopApp(onActiveTitleChange = { windowTitle = it })
            }
        }
    } finally {
        singleInstance.close()
    }
}

private fun DesktopWindowActivationHandler.handleCommand(command: DesktopIpcCommand) {
    when (command) {
        DesktopIpcCommand.ShowUserThatAppIsRunning -> showUserThatAppIsRunning()
    }
}
