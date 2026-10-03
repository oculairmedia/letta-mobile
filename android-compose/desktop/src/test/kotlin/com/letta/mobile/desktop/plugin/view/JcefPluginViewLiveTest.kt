package com.letta.mobile.desktop.plugin.view

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.CefApp
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The desktop live view in a real JCEF browser (letta-mobile-s416w.14). Opt-in, because it needs a
 * display and the JCEF native bundle (downloaded on first run):
 *
 *     ./gradlew :desktop:test --tests '*JcefPluginViewLiveTest*' -PrunJcefUiTest=true
 *
 * The first test proves the shim round trip over the `letta-plugin` scheme and the message router
 * (ready, host context, a log line, CSP blocking an unlisted origin) and an acknowledged teardown.
 * The second puts a live element on a real canvas in a Compose window, then opens another canvas
 * the way the app does, proving the SwingPanel neither breaks the canvas nor outlives it.
 */
class JcefPluginViewLiveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cancelScope() = scope.cancel()

    @Test
    fun aPageRoundTripsThroughTheShimAndAcknowledgesItsTeardown() {
        val app = startedApp()
        val host = DesktopPluginViewHost(sharedRuntime, scope)
        val viewHost = RecordingViewHost()
        val faults = mutableListOf<String>()
        val frame = AtomicReference<JFrame>()
        val session = AtomicReference<PluginViewSession>()
        SwingUtilities.invokeAndWait {
            session.set(host.open(app, liveView(viewHost)) { faults += it })
            frame.set(JFrame("live view").apply { add(session.get().component); setSize(WIDTH, HEIGHT); isVisible = true })
        }
        try {
            awaitLog(viewHost, "ready:el-1:desktop")
            awaitLog(viewHost, "connect-blocked")
            awaitLog(viewHost, "origin:letta-plugin://letta.example")
            assertTrue(runBlocking { session.get().elementChanged(PluginViewTestFixtures.element) })
            awaitLog(viewHost, "changed:el-1")
            assertTrue(runBlocking { session.get().close(PluginViewTeardown.CLOSED) }, "the page acknowledged host.teardown")
            assertEquals(emptyList(), faults)
        } finally {
            SwingUtilities.invokeAndWait { frame.get().dispose() }
        }
    }

    @Test
    fun aLiveElementOnACanvasSurvivesOpeningAnotherCanvas() {
        startedApp()
        val host = DesktopPluginViewHost(sharedRuntime, scope)
        val viewHost = RecordingViewHost()
        val failure = AtomicReference<Throwable?>(null)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> failure.compareAndSet(null, thrown) }
        val bindings = DesktopPluginBindings(setOf("ext:letta.example/"), PluginLiveViewSource { _, _ -> liveView(viewHost) })
        val board = mutableStateOf(boardWithWidget())
        val window = AtomicReference<ComposeWindow>()
        SwingUtilities.invokeAndWait {
            window.set(
                ComposeWindow().apply {
                    setContent {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            ProvideDesktopPluginViews(bindings, host) {
                                val current = board.value
                                key(current) { CanvasWorkspace(session = current) }
                            }
                        }
                    }
                    setSize(CANVAS_WIDTH, CANVAS_HEIGHT)
                    isVisible = true
                },
            )
        }
        try {
            awaitLog(viewHost, "ready:el-1:desktop")
            assertEquals(1, host.openViews)
            SwingUtilities.invokeAndWait { board.value = emptyBoard() }
            runBlocking { withTimeout(WAIT_MS) { while (host.openViews > 0) delay(POLL_MS) } }
            Thread.sleep(SETTLE_MS)
            assertNull(failure.get(), "nothing was thrown while the canvas with the live view closed")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            SwingUtilities.invokeAndWait { window.get().dispose() }
        }
    }

    private fun startedApp(): CefApp {
        assumeTrue(System.getProperty(PROPERTY) == "true", "opt in with -P$PROPERTY=true")
        assumeTrue(!GraphicsEnvironment.isHeadless(), "needs a display")
        return runBlocking { withTimeout(INSTALL_MS) { sharedRuntime.await() } }
            ?: fail("JCEF did not start: ${sharedRuntime.state.value}")
    }

    private fun liveView(viewHost: RecordingViewHost) =
        PluginLiveView(PluginViewTestFixtures.spec(), ViewBridgeServices(viewHost, FakePageTransport(PAGE)))

    private fun awaitLog(viewHost: RecordingViewHost, line: String) = runBlocking {
        withTimeout(WAIT_MS) { while (line !in viewHost.logs) delay(POLL_MS) }
    }

    private fun emptyBoard(): CanvasSession = runBlocking {
        CanvasSession.create(store = InMemoryCanvasDocumentStore(), options = CanvasCreateOptions(title = "Other", initialSceneJson = ""))
    }

    private fun boardWithWidget(): CanvasSession = emptyBoard().also { session ->
        runBlocking {
            session.applyLocalStamped(
                listOf(
                    CanvasOp.SetPluginElementOp(
                        opId = "",
                        actorId = CanvasSession.LOCAL_USER_ACTOR_ID,
                        lamport = 0L,
                        elementId = "el-1",
                        elementType = "ext:letta.example/widget",
                        v = 1,
                        frame = CanvasDocumentFrame(40f, 40f, 360f, 260f),
                        fallback = CanvasPluginFallback(title = "Widget"),
                    ),
                ),
            )
        }
    }

    private companion object {
        const val PROPERTY = "runJcefUiTest"
        const val WIDTH = 480
        const val HEIGHT = 360
        const val CANVAS_WIDTH = 1100
        const val CANVAS_HEIGHT = 760
        const val WAIT_MS = 60_000L
        const val INSTALL_MS = 15 * 60_000L
        const val POLL_MS = 50L
        const val SETTLE_MS = 1_000L

        /** One runtime for the whole test JVM: CEF initialises once per process. */
        val sharedRuntime: BrowserRuntime<CefApp> by lazy {
            BrowserRuntime(JcefAppStarter(JcefConfig.fromSystem()), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }

        val PAGE = """
            <!doctype html><html><head><title>live</title></head>
            <body style="background:#1d2b4f;color:#fff;font:16px sans-serif"><h1>Live widget</h1>
            <script>
              addEventListener("message", function (e) {
                if (e.data.method === "host.element.changed") lettaView.log("info", "changed:" + e.data.params.element.id);
              });
              lettaView.ready().then(function (ctx) {
                lettaView.log("info", "ready:" + ctx.element.id + ":" + ctx.platform);
                lettaView.log("info", "origin:" + location.origin);
                fetch("https://evil.example.org/x").then(
                  function () { lettaView.log("info", "connect-allowed"); },
                  function () { lettaView.log("info", "connect-blocked"); });
              });
            </script></body></html>
        """.trimIndent()
    }
}
