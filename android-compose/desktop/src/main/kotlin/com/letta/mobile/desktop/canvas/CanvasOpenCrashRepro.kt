package com.letta.mobile.desktop.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.desktop.initializeDesktopLifecycleMainThread
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel
import kotlin.system.exitProcess

/**
 * Reproduces, without a person driving it, the crash that kills the app when a NEW canvas is
 * opened from the composer menu:
 *
 *     java.lang.IllegalArgumentException: RootNodeOwner is already disposed
 *
 * It is a race, so it is driven in a loop rather than once. The shape below is the shape of the
 * real failure, reduced to its parts: a real window with a Direct3D redrawer (not a headless test
 * scene), Swing interop in the hierarchy (the app embeds JCEF, and `SwingInteropContainer` sits in
 * the crash's render path), a menu that is its own scene layer, and an item that replaces the
 * whole screen with a freshly CREATED canvas session. Opening an existing canvas never fails;
 * creating one does, which points at how long the create takes rather than at either code path.
 *
 * Run it with:
 *     ./gradlew :desktop:runCanvasCrashRepro
 *
 * It exits 1 and prints REPRO-FAILED with the throwable when it reproduces, 0 and REPRO-CLEAN when
 * it survives every round.
 */
private const val ROUNDS = 40

private val failure = AtomicReference<Throwable?>(null)

fun main() {
    initializeDesktopLifecycleMainThread()

    // The crash lands on the AWT event thread inside the redrawer, where nothing in the app's own
    // call stack can catch it. This is how the app itself learns about it, and how we do.
    Thread.setDefaultUncaughtExceptionHandler { _, thrown -> failure.compareAndSet(null, thrown) }
    System.setProperty("sun.awt.exception.handler", ReproAwtExceptionHandler::class.java.name)

    singleWindowApplication(
        title = "Canvas open crash repro",
        state = WindowState(width = 1280.dp, height = 820.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) { ReproContent(window) }
        }
    }

    val thrown = failure.get()
    if (thrown == null) {
        println("REPRO-CLEAN: $ROUNDS rounds, no crash")
        exitProcess(0)
    }
    println("REPRO-FAILED: ${thrown::class.java.name}: ${thrown.message}")
    thrown.stackTrace.take(12).forEach { println("\tat $it") }
    exitProcess(1)
}

/** Catches what AWT throws on the event thread, which is where the disposal error surfaces. */
class ReproAwtExceptionHandler {
    @Suppress("unused")
    fun handle(thrown: Throwable) {
        failure.compareAndSet(null, thrown)
    }
}

@Composable
private fun ReproContent(window: java.awt.Window) {
    var session by remember { mutableStateOf<CanvasSession?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }

    // An idle Compose window stops producing frames, and anything waiting on one waits for good.
    // This keeps it invalidating so the repro always has a frame to be caught out by.
    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            tick++
        }
    }

    // The app's own event queue and window chrome. The touch queue is in EVERY crash stack we
    // have, wrapping the dispatch the disposal throws from, and it is installed by the real app
    // and not by a plain singleWindowApplication - so the reduction without it was not the same
    // program.
    LaunchedEffect(window) {
        runCatching { com.letta.mobile.desktop.DesktopWindowsChrome.applyStandardChrome(window) }
        runCatching { com.letta.mobile.desktop.touch.DesktopWindowsTouchInput.attach(window) }
    }

    LaunchedEffect(Unit) {
        val store = DesktopCanvasDocumentStore()
        for (index in 0 until ROUNDS) {
            round = index
            // Open the menu, let it lay out as its own scene layer, then choose the item - the
            // sequence a hand performs, at the speed a machine performs it.
            println("REPRO-ROUND $index")
            menuOpen = true
            delay(80)
            menuOpen = false

            // Creating is the slow half, and the half that fails. It runs off the frame, exactly
            // as the app's own open-canvas action does.
            val created = withContext(Dispatchers.Default) {
                CanvasSession.getOrCreateForConversation(
                    store = store,
                    conversationId = "repro-conversation-$index",
                    options = CanvasConversationOptions(agentId = null, title = "Repro $index"),
                )
            }
            session = created
            delay(160)

            // Back to nothing, so the next round creates another one.
            session = null
            delay(80)
            if (failure.get() != null) break
        }
        // Give the redrawer a few more frames to throw on what it was left holding.
        delay(400)
        reportAndExit()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val active = session
        if (active == null) {
            Text("round $round tick $tick: no canvas")
            // Swing interop in the hierarchy, as JCEF is in the real window: the crash's render
            // path goes through SwingInteropContainer.
            SwingPanel(factory = { JPanel() }, modifier = Modifier.fillMaxSize())
        } else {
            CanvasWorkspace(session = active)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Open canvas") }, onClick = { menuOpen = false })
        }
    }
}

/**
 * Prints the verdict and ends the process.
 *
 * Disposing the window was not enough to end it - the run hung instead of reporting - and a repro
 * that does not report is no better than clicking by hand.
 */
private fun reportAndExit() {
    val thrown = failure.get()
    if (thrown == null) {
        println("REPRO-CLEAN: $ROUNDS rounds, no crash")
        exitProcess(0)
    }
    println("REPRO-FAILED: ${thrown::class.java.name}: ${thrown.message}")
    thrown.stackTrace.take(14).forEach { println("	at $it") }
    exitProcess(1)
}
