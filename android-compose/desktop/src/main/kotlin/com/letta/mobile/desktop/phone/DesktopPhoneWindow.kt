package com.letta.mobile.desktop.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.desktop.avatar.rive.RiveBridgeNative
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlinx.coroutines.delay

/**
 * A desktop window that is a phone: sized to [DesktopPhoneState.device] (scaled down only when the
 * monitor is too small), resizable, drawing [content] through [DesktopPhoneScreen], with the phone
 * shortcuts ([PhoneShortcut]) on its keys and its settings in the title.
 *
 * Two environment variables help scripted runs: `LETTA_PHONE_AUTOSHOT=<seconds>` saves a screenshot
 * that long after the window opens, and `LETTA_PHONE_EXIT_AFTER_SHOT=1` then closes it.
 */
@Composable
internal fun DesktopPhoneWindow(
    state: DesktopPhoneState,
    titlePrefix: String,
    onCloseRequest: () -> Unit,
    content: @Composable (ComposeWindow) -> Unit,
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = state.windowSizeFor(landscape = false),
    )
    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = state.titleSummary(titlePrefix),
        onPreviewKeyEvent = { event -> PhoneShortcut.of(event)?.let(state::apply) != null },
    ) {
        LaunchedEffect(window) {
            DesktopPhoneTouchPointer.install(window) { state.touchPointer }
            window.fitContent(windowState, state.windowSizeFor(state.landscape))
            reportRiveBridge()
        }
        LaunchedEffect(state.landscape, state.deviceFrame) {
            window.fitContent(windowState, state.windowSizeFor(state.landscape))
        }
        LaunchedEffect(Unit) {
            delay(SHORTCUT_LIST_MILLIS)
            state.showShortcuts = false
        }
        PhoneScreenshots(window, state, titlePrefix, onCloseRequest)
        DesktopPhoneScreen(state) { content(window) }
    }
}

/**
 * Takes the screenshots [state] asks for (Ctrl+Shift+S) and, for scripted runs, the one
 * `LETTA_PHONE_AUTOSHOT=<seconds>` asks for, after which `LETTA_PHONE_EXIT_AFTER_SHOT=1` calls [onClose].
 */
@Composable
internal fun PhoneScreenshots(window: ComposeWindow, state: DesktopPhoneState, prefix: String, onClose: () -> Unit) {
    LaunchedEffect(window, state.screenshotRequests) {
        if (state.screenshotRequests == 0) return@LaunchedEffect
        val file = savePhoneScreenshot(window, prefix)
        state.announce(if (file != null) "Saved ${file.name}" else "Screenshot failed")
    }
    val seconds = remember { System.getenv("LETTA_PHONE_AUTOSHOT")?.toDoubleOrNull() } ?: return
    LaunchedEffect(window) {
        delay((seconds * MILLIS_PER_SECOND).toLong())
        state.showShortcuts = false
        delay(SETTLE_BEFORE_SHOT_MILLIS)
        savePhoneScreenshot(window, prefix)
        if (System.getenv("LETTA_PHONE_EXIT_AFTER_SHOT") == "1") onClose()
    }
}

/** The window's size for the phone's screen plus its frame, in the window's dp (AWT's logical pixels). */
internal fun DesktopPhoneState.windowSizeFor(landscape: Boolean): DpSize {
    val frame = if (deviceFrame) 2 * FRAME_DP else 0
    val width = device.widthDp * zoom + frame
    val height = device.heightDp * zoom + frame
    return if (landscape) DpSize(height.dp, width.dp) else DpSize(width.dp, height.dp)
}

/** Sizes the window so its content area (not its title bar and borders) is [content]. */
private fun ComposeWindow.fitContent(state: WindowState, content: DpSize) {
    val insets = insets
    state.size = DpSize(content.width + (insets.left + insets.right).dp, content.height + (insets.top + insets.bottom).dp)
}

/**
 * The phone's zoom for this monitor: 1 (one phone dp per window dp) when the phone and its window
 * chrome fit the usable screen height, else the largest step of 0.05 that does.
 */
internal fun phoneZoomFor(device: PhoneDevice): Float {
    val usable = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull() ?: return 1f
    val available = minOf(usable.height - WINDOW_CHROME_DP, usable.width - WINDOW_CHROME_DP).toFloat()
    val needed = (device.heightDp + 2 * FRAME_DP).toFloat()
    if (available >= needed) return 1f
    return (floor(available / needed / ZOOM_STEP) * ZOOM_STEP).toFloat().coerceAtLeast(MIN_ZOOM)
}

/**
 * Saves the window's content (the phone and its frame) to build/phone-preview/; null when it could
 * not. A screen capture of the window's area, taken with the window briefly kept on top so other
 * windows do not end up in the picture. (Reading the Skia surface back directly crashes under the
 * Direct3D renderer.)
 */
internal suspend fun savePhoneScreenshot(window: ComposeWindow, prefix: String): File? = runCatching {
    val wasOnTop = window.isAlwaysOnTop
    window.isAlwaysOnTop = true
    val image = try {
        delay(SETTLE_BEFORE_SHOT_MILLIS)
        screenCapture(window)
    } finally {
        window.isAlwaysOnTop = wasOnTop
    }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val slug = prefix.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val file = File(PHONE_PREVIEW_OUTPUT_DIR).apply { mkdirs() }.resolve("$slug-$stamp.png")
    ImageIO.write(image, "png", file)
    println("PHONE: screenshot saved to ${file.absolutePath}")
    file
}.onFailure { println("PHONE: screenshot failed: ${it.message}") }.getOrNull()

private fun screenCapture(window: ComposeWindow): BufferedImage {
    val pane = window.contentPane
    val origin = pane.locationOnScreen
    val capture = Robot().createMultiResolutionScreenCapture(Rectangle(origin.x, origin.y, pane.width, pane.height))
    return capture.resolutionVariants.maxBy { it.getWidth(null) } as BufferedImage
}

/** Logs, loudly, when the native Rive bridge is missing: the mascots then fall back to orbs. */
internal fun reportRiveBridge() {
    if (RiveBridgeNative.AVAILABLE) {
        println("PHONE: Rive bridge loaded from ${RiveBridgeNative.PATH}")
        return
    }
    println(
        "PHONE: WARNING - rive_desktop_bridge.dll not found (looked at -Drive.bridge.path, the app resources dir and " +
            "${File("rive_desktop_bridge.dll").absolutePath}). Mascots fall back to orbs. Copy the DLL into " +
            "android-compose/desktop/ (it is git-ignored) or pass -Drive.bridge.path=<dll>.",
    )
}

/** Where screenshots go, relative to the working directory (android-compose/desktop under Gradle). */
internal const val PHONE_PREVIEW_OUTPUT_DIR = "build/phone-preview"

/** Matches DesktopPhoneScreen's frame width. */
private const val FRAME_DP = 10
private const val WINDOW_CHROME_DP = 48
private const val ZOOM_STEP = 0.05
private const val MIN_ZOOM = 0.4f
private const val SHORTCUT_LIST_MILLIS = 8_000L
private const val MILLIS_PER_SECOND = 1000
private const val SETTLE_BEFORE_SHOT_MILLIS = 300L
