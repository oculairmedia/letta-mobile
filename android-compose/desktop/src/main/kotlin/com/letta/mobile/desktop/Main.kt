package com.letta.mobile.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.desktop.markdown.DesktopMermaidDiagramRenderer
import com.letta.mobile.ui.markdown.LocalMermaidDiagramRenderer
import java.awt.Dimension
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    DesktopCrashReporter.installGlobalHandler()
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

@OptIn(ExperimentalComposeUiApi::class)
private fun runDesktopApplication(
    singleInstance: DesktopSingleInstance.Primary,
    activationHandler: DesktopWindowActivationHandler,
) {
    singleInstance.use {
        application {
            var windowTitle by remember { mutableStateOf("Letta Desktop") }
            CompositionLocalProvider(
                LocalWindowExceptionHandlerFactory provides CrashReportingExceptionHandlerFactory,
                LocalMermaidDiagramRenderer provides DesktopMermaidDiagramRenderer,
            ) {
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
        }
    }
}

/**
 * Replaces Compose Desktop's default window exception handler (which surfaces
 * only the throwable's message — a raw internal class name for code-loading
 * errors) with one that writes the full stack trace to the crash log and shows
 * a readable, actionable dialog before exiting.
 */
@OptIn(ExperimentalComposeUiApi::class)
private val CrashReportingExceptionHandlerFactory = WindowExceptionHandlerFactory { window ->
    WindowExceptionHandler { throwable ->
        DesktopCrashReporter.logCrash(throwable, context = "window composition")
        val message = buildString {
            append(DesktopCrashReporter.userMessage(throwable))
            append("\n\nA crash log was written to:\n")
            append(DesktopCrashReporter.crashLogPath())
        }
        runCatching {
            JOptionPane.showMessageDialog(window, message, "Letta Desktop", JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }
}

private fun DesktopWindowActivationHandler.handleCommand(command: DesktopIpcCommand) {
    when (command) {
        DesktopIpcCommand.ShowUserThatAppIsRunning -> showUserThatAppIsRunning()
    }
}
