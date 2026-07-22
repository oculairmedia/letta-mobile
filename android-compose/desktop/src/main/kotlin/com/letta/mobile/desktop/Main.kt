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
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.desktop.markdown.DesktopMermaidDiagramRenderer
import com.letta.mobile.ui.markdown.LocalMermaidDiagramRenderer
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.SingleInstanceRestoreEffect
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.hidpi.applyLinuxHiDpiScale
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.launcher.windows.WindowsJumpListManager
import java.awt.Dimension
import javax.swing.JOptionPane
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.system.exitProcess

internal const val LETTA_WINDOWS_AUMID = "com.letta.desktop"
internal const val LETTA_DESKTOP_APP_NAME = "Letta Desktop"

fun main(args: Array<String>) {
    applyLinuxHiDpiScale()
    DesktopCrashReporter.installGlobalHandler()
    initializeDesktopLifecycleMainThread()
    if (Platform.Current == Platform.Windows) {
        System.setProperty("nucleus.app.aumid", LETTA_WINDOWS_AUMID)
        WindowsJumpListManager.setProcessAppId(LETTA_WINDOWS_AUMID)
    }
    val activationHandler = DesktopWindowActivationHandler()
    runDesktopApplication(args, activationHandler)
}

/**
 * Compose 1.11 can create its first architecture owner before Lifecycle has a
 * usable Swing Main dispatcher. Lifecycle's discovery then uses
 * runBlocking(Dispatchers.Main.immediate), which waits forever because no AWT
 * event queue can service it yet. Use Lifecycle's own "dispatcher unavailable"
 * fallback during bootstrap; Compose continues to own UI confinement on AWT.
 *
 * MainDispatcherChecker is internal Kotlin API, so reflection keeps this
 * narrowly scoped and lets a future Lifecycle upgrade remove the workaround
 * without exposing that implementation detail to the rest of the app.
 */
private fun initializeDesktopLifecycleMainThread() {
    runCatching {
        val checkerClass = Class.forName("androidx.lifecycle.MainDispatcherChecker")
        checkerClass.getDeclaredField("isMainDispatcherAvailable").apply {
            isAccessible = true
            setBoolean(null, false)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun runDesktopApplication(
    args: Array<String>,
    activationHandler: DesktopWindowActivationHandler,
) {
    val deepLinkSequence = AtomicLong()
    val deepLinks = MutableStateFlow<DesktopDeepLinkRequest?>(null)
    nucleusApplication(
        args = args,
        backend = NucleusBackend.Awt,
        enableSingleInstance = true,
    ) {
            val nucleusScope = this
            onDeepLink { uri ->
                deepLinks.value = DesktopDeepLinkRequest(deepLinkSequence.incrementAndGet(), uri)
                activationHandler.showUserThatAppIsRunning()
            }
            // A second launch with no deep link (desktop/Start Menu icon while
            // the primary is hidden to the tray) must still restore the window.
            SingleInstanceRestoreEffect {
                activationHandler.showUserThatAppIsRunning()
            }
            var windowTitle by remember { mutableStateOf("Letta Desktop") }
            CompositionLocalProvider(
                LocalWindowExceptionHandlerFactory provides CrashReportingExceptionHandlerFactory,
                LocalMermaidDiagramRenderer provides DesktopMermaidDiagramRenderer,
            ) {
                DesktopJewelWindow(
                    // Keep background agents and schedules alive; Quit remains
                    // available from the Nucleus native tray menu.
                    onCloseRequest = { activationHandler.hideWindow() },
                    title = windowTitle,
                    state = rememberWindowState(width = 1280.dp, height = 820.dp),
                ) {
                    LaunchedEffect(Unit) {
                        activationHandler.attach(window)
                        window.minimumSize = Dimension(960, 640)
                    }

                    LettaDesktopApp(
                        nucleusApplicationScope = nucleusScope,
                        window = window,
                        deepLinks = deepLinks,
                        onActiveTitleChange = { windowTitle = it },
                    )
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
