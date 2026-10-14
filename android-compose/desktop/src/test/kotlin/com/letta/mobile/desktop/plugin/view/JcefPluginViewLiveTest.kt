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
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginContract
import com.letta.mobile.data.plugin.PluginElementKind
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.pluginview.PluginViewEnvironment
import com.letta.mobile.pluginview.PluginViewPlugin
import com.letta.mobile.pluginview.ProvidePluginViews
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.CefApp
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.GraphicsEnvironment
import java.util.concurrent.CopyOnWriteArrayList
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
 * The first test proves the shim round trip at the shared policy's `.invalid` page URL over the
 * message router (ready, host context, a log line, CSP blocking an unlisted origin, the opaque
 * origin of `sandbox allow-scripts`) and an acknowledged teardown. The second puts a live element
 * on a real canvas in a Compose window through the shared `ProvidePluginViews` and the
 * [JcefPluginViewHost], then opens another canvas the way the app does, proving the SwingPanel
 * neither breaks the canvas nor outlives it.
 */
class JcefPluginViewLiveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cancelScope() = scope.cancel()

    @Test
    fun aPageRoundTripsThroughTheShimAndAcknowledgesItsTeardown() {
        val app = startedApp()
        val views = JcefLiveViews(sharedRuntime, PluginViewWork(scope), JcefPluginBrowser)
        val viewHost = RecordingViewHost()
        val session = PluginViewTestFixtures.session(viewHost, FakePageTransport(PAGE))
        val faults = CopyOnWriteArrayList<String>()
        val frame = AtomicReference<JFrame>()
        val view = AtomicReference<JcefLiveView>()
        SwingUtilities.invokeAndWait {
            view.set(views.open(app, session) { faults += it })
            frame.set(JFrame("live view").apply { add(view.get().component); setSize(WIDTH, HEIGHT); isVisible = true })
        }
        try {
            awaitLog(viewHost, "ready:el-1:desktop")
            awaitLog(viewHost, "connect-blocked")
            awaitLog(viewHost, "origin:null")
            assertTrue(runBlocking { session.bridge.elementChanged(PluginViewTestFixtures.element) })
            awaitLog(viewHost, "changed:el-1")
            assertTrue(runBlocking { view.get().close(PluginViewTeardown.CLOSED) }, "the page acknowledged host.teardown")
            assertEquals(emptyList(), faults)
        } finally {
            SwingUtilities.invokeAndWait { frame.get().dispose() }
        }
    }

    @Test
    fun aLiveElementOnACanvasSurvivesOpeningAnotherCanvas() {
        startedApp()
        val views = JcefLiveViews(sharedRuntime, PluginViewWork(scope), JcefPluginBrowser)
        val failure = AtomicReference<Throwable?>(null)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> failure.compareAndSet(null, thrown) }
        val environment = PluginViewEnvironment(canvasId = "canvas-1", platform = ViewPlatform.DESKTOP, transport = FakePageTransport(PAGE))
        val board = mutableStateOf(boardWithWidget())
        val window = AtomicReference<ComposeWindow>()
        SwingUtilities.invokeAndWait {
            window.set(
                ComposeWindow().apply {
                    setContent {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            ProvidePluginViews(JcefPluginViewHost(views), environment, listOf(PluginViewPlugin(MANIFEST))) {
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
            runBlocking { withTimeout(WAIT_MS) { while (views.openViews < 1) delay(POLL_MS) } }
            assertEquals(1, views.openViews)
            SwingUtilities.invokeAndWait { board.value = emptyBoard() }
            runBlocking { withTimeout(WAIT_MS) { while (views.openViews > 0) delay(POLL_MS) } }
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

        /** The plugin whose `widget` kind has the live page. */
        val MANIFEST = PluginManifest(
            manifestVersion = 1,
            id = "letta.example",
            name = "Example",
            version = "1.2.0",
            publisher = "oculairmedia",
            contract = PluginContract(1),
            runtime = PluginRuntime.Jvm("plugin.jar", "com.example.ExamplePlugin"),
            settings = emptyMap(),
            capabilities = listOf(PluginCapability.UI_PAGES),
            elements = mapOf("widget" to PluginElementKind(schemaVersion = 1, props = JsonObject(emptyMap()), page = "widget")),
            pages = mapOf("widget" to PluginViewTestFixtures.page),
        )

        /** One runtime for the whole test JVM: CEF initialises once per process. */
        val sharedRuntime: BrowserRuntime<CefApp> by lazy {
            BrowserRuntime(JcefAppStarter(JcefConfig.fromSystem()))
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
                lettaView.log("info", "origin:" + self.origin);
                fetch("https://evil.example.org/x").then(
                  function () { lettaView.log("info", "connect-allowed"); },
                  function () { lettaView.log("info", "connect-blocked"); });
              });
            </script></body></html>
        """.trimIndent()
    }
}
