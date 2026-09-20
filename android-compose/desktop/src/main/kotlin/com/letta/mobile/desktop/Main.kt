package com.letta.mobile.desktop

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import com.letta.mobile.desktop.touch.DesktopTouchKeyboardHost
import com.letta.mobile.desktop.touch.DesktopWindowsTouchInput
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

/**
 * How much smaller desktop draws than the shared Android-density UI.
 *
 * 0.8 was chosen against the Home dashboard, the recents list and the composer,
 * the three surfaces where the Android sizing was most obviously wrong on a
 * monitor. Override at runtime with `-Dletta.desktop.density=0.9` to compare
 * without a rebuild; values outside 0.5..1.0 are ignored.
 */
private val DESKTOP_DENSITY_SCALE: Float =
    System.getProperty("letta.desktop.density")?.toFloatOrNull()?.takeIf { it in 0.5f..1.0f } ?: 0.8f

internal const val LETTA_WINDOWS_AUMID = "com.letta.desktop"
internal const val LETTA_DESKTOP_APP_NAME = "Letta Desktop"

fun main(args: Array<String>) {
    applyLinuxHiDpiScale()
    DesktopCrashReporter.installGlobalHandler()
    initializeDesktopLifecycleMainThread()
    // Kotzilla observability — must run before any UI composition so the SDK
    // captures the full session including startup. Android target boots via
    // ContentProvider; Desktop (and any future WasmJS) call this wrapper
    // explicitly. See sharedLogic/commonMain/.../KotzillaKmpMonitoring.kt.
    com.letta.mobile.data.observability.startKotzillaMonitoring()
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
internal fun initializeDesktopLifecycleMainThread() {
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
    val quickQuery = DesktopQuickQueryCoordinator()
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
            // Lifted the same way as windowTitle: LettaDesktopApp owns the live
            // chat/shell state the header needs (identity block + sidebar
            // toggle), but the header itself renders in the custom title bar,
            // which is a composition sibling — not a descendant — of
            // LettaDesktopApp. See letta-mobile-3arhe.1.
            var headerChrome by remember { mutableStateOf(DesktopHeaderChromeState.Empty) }
            val mascots = remember { com.letta.mobile.ui.mascot.MascotIdentityRegistry() }
            // The transport verb: one mascot per agent, moved between the seats the surfaces declare.
            val mascotTransport = remember { com.letta.mobile.ui.mascot.MascotTransport() }
            // Desktop density. Compose's shared UI is authored at Android's
            // touch density, where a 48dp row is a finger target. On a desktop
            // pointer display that same row is simply oversized: the fleet
            // dashboard, the recents list and the composer all read as a phone
            // blown up to fill a monitor.
            //
            // Scaling LocalDensity rather than editing dimensions fixes every
            // surface at once, including the ~2600 literals the alignment pass
            // (letta-mobile-hzddx) has not reached yet, and it keeps one set of
            // numbers shared with Android instead of forking them per platform.
            // Font scale is deliberately left alone: text is already sized by
            // the type scale, and scaling both compounds into unreadably small
            // labels.
            val platformDensity = LocalDensity.current
            val desktopDensity = remember(platformDensity) {
                Density(
                    density = platformDensity.density * DESKTOP_DENSITY_SCALE,
                    fontScale = platformDensity.fontScale,
                )
            }
            CompositionLocalProvider(
                LocalDensity provides desktopDensity,
                LocalWindowExceptionHandlerFactory provides CrashReportingExceptionHandlerFactory,
                LocalMermaidDiagramRenderer provides DesktopMermaidDiagramRenderer,
                // The native Rive bridge draws every live mascot; the shared MascotAvatar reads it here,
                // with the window-owned registry of identities, presence and the pointer.
                com.letta.mobile.ui.mascot.LocalMascotHost provides com.letta.mobile.desktop.avatar.rive.DesktopMascotHost,
                com.letta.mobile.ui.mascot.LocalMascotRegistry provides mascots,
                com.letta.mobile.ui.mascot.LocalMascotTransport provides mascotTransport,
            ) {
                // Windows touchscreens: every text field that starts an input
                // session while the last pointer input came from a finger gets
                // the OS touch keyboard raised (and dismissed with the session).
                DesktopTouchKeyboardHost {
                    DesktopJewelWindow(
                        // Keep background agents and schedules alive; Quit remains
                        // available from the Nucleus native tray menu.
                        onCloseRequest = { activationHandler.hideWindow() },
                        title = windowTitle,
                        state = rememberWindowState(width = 1280.dp, height = 820.dp),
                        header = headerChrome,
                    ) {
                        LaunchedEffect(Unit) {
                            activationHandler.attach(window)
                            window.minimumSize = Dimension(960, 640)
                            // Windows 11 standard rounded corners + outline on the
                            // undecorated frame.
                            DesktopWindowsChrome.applyStandardChrome(window)
                            // Touch drag-to-scroll: AWT hands Compose every
                            // WM_TOUCH as a PointerType.Mouse event, which
                            // Compose Foundation refuses to drag-scroll.
                            DesktopWindowsTouchInput.attach(window)
                        }

                        // Ctrl+scroll scales app type, persisted across
                        // launches. Wraps the shell only, so the custom title
                        // bar (a composition sibling) keeps its fixed chrome
                        // metrics the way browser zoom leaves the browser's own
                        // chrome alone.
                        DesktopChatFontScaleHost {
                            // Every mascot in the app looks toward the cursor; capture it once, at the root.
                            MascotCursorCapture(mascots) {
                            LettaDesktopApp(
                                shell = DesktopAppShellBindings(
                                    nucleusApplicationScope = nucleusScope,
                                    window = window,
                                    deepLinks = deepLinks,
                                    quickQuery = quickQuery,
                                ),
                                onActiveTitleChange = { windowTitle = it },
                                onHeaderChromeChange = { headerChrome = it },
                            )
                            }
                        }
                    }
                    // Spotlight-style floating query bar, summoned by the global
                    // hotkey without raising the main window.
                    DesktopQuickQueryWindow(quickQuery)
                    DebugWindows()
                }
            }
        }
}

/**
 * The extra windows that only open when their environment variable is set. They are kept together,
 * and out of [runDesktopApplication], because each one is a whole verification surface that has
 * nothing to do with bringing up the app - and because appending the next one to the startup path
 * is how that function grows without anyone deciding it should.
 */
@Composable
private fun DebugWindows() {
    // Realtime shader lookdev, for tuning the ambient glow against the real app theme and
    // backend state.
    if (System.getenv("LETTA_SHADER_LOOKDEV") == "1") {
        com.letta.mobile.desktop.lookdev.ShaderLookdevWindow()
    }
    // Canvas Workspace verification host.
    if (System.getenv("MERIDIAN_CANVAS_DEBUG") == "1") {
        com.letta.mobile.desktop.canvas.CanvasDebugWindow()
    }
}

/**
 * Replaces Compose Desktop's default window exception handler (which surfaces
 * only the throwable's message — a raw internal class name for code-loading
 * errors) with one that writes the full stack trace to the crash log and shows
 * a readable, actionable dialog before exiting.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal val CrashReportingExceptionHandlerFactory = WindowExceptionHandlerFactory { window ->
    WindowExceptionHandler { throwable ->
        if (isRecoverableRenderError(throwable)) {
            // Not fatal, and not ours to die over: the frame was drawn against a scene layer - a
            // menu, a tooltip, a dialog - that was disposed between the frame being scheduled and
            // being drawn. Compose draws the next frame against a live scene.
            //
            // This handler used to exit for ANY throwable, which is what actually closed the app
            // when a new canvas was opened: the race below is Compose's, but killing the process
            // over it was ours. Logged once so the underlying race stays visible without filling
            // the log a frame at a time.
            // Counted, not just noticed. Logging once per session answers "did it happen" and
            // nothing else - not how often, not whether a flash is one dropped frame or twenty in
            // a row. The count and the gap since the previous drop are what say how bad it is.
            val dropped = recoverableRenderFrames.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastRecoverableRenderFrame.getAndSet(now)
            val sincePrevious = if (previous == 0L) -1L else (now - previous) / NANOS_PER_MILLI
            println("RENDER: dropped frame #$dropped" + if (sincePrevious < 0) " (first)" else " (+${sincePrevious}ms)")
            if (dropped == 1L) {
                DesktopCrashReporter.logCrash(throwable, context = "recoverable render frame")
            }
            return@WindowExceptionHandler
        }
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

/** How many frames this session has lost to the disposed-layer race. */
private val recoverableRenderFrames = java.util.concurrent.atomic.AtomicLong(0)

/** When the last one was, so the gap between drops says whether they cascade. */
private val lastRecoverableRenderFrame = java.util.concurrent.atomic.AtomicLong(0)

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * True for the render errors the app should survive rather than exit on.
 *
 * Compose throws when it measures a scene layer whose root node was disposed after the frame was
 * scheduled - closing a menu while the screen behind it is replaced is enough. The frame is lost
 * either way; the only question is whether the app goes with it.
 *
 * Deliberately narrow: the owner Compose names, on the one exception type. "is already disposed"
 * on its own was too broad - a session, a transport or any other object reporting the same phrase
 * would have been swallowed with it, and an app that hides the faults that matter is worse than
 * one that exits on a frame it could have survived.
 */
internal fun isRecoverableRenderError(throwable: Throwable): Boolean =
    throwable is IllegalArgumentException &&
        throwable.message?.contains(DISPOSED_SCENE_OWNER, ignoreCase = true) == true

/** What Compose throws when it measures a scene layer whose root node has gone. */
private const val DISPOSED_SCENE_OWNER = "RootNodeOwner is already disposed"
