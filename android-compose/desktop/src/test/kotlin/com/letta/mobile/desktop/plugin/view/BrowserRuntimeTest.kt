package com.letta.mobile.desktop.plugin.view

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared embedded-browser runtime (letta-mobile-s416w.14): started once and lazily, its
 * first-run install progress visible, and a failure (offline on first run, no display, turned off)
 * final and non-fatal, carrying the reason the cards show.
 */
class BrowserRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cancelScope() = scope.cancel()

    @Test
    fun itStartsOnceAndOnlyWhenAViewAsks() = runBlocking {
        val starts = AtomicInteger()
        val runtime = BrowserRuntime(BrowserStarter { starts.incrementAndGet(); "browser" }, scope, Dispatchers.Default)
        assertEquals(BrowserRuntimeState.Idle, runtime.state.value)
        assertEquals(0, starts.get())

        assertEquals("browser", withTimeout(WAIT_MS) { runtime.await() })
        runtime.ensureStarted()
        assertEquals("browser", runtime.await())
        assertEquals("browser", runtime.readyOrNull())
        assertEquals(1, starts.get())
    }

    @Test
    fun theFirstRunReportsItsInstallProgress() = runBlocking {
        val seen = CopyOnWriteArrayList<BrowserRuntimeState<String>>()
        lateinit var runtime: BrowserRuntime<String>
        runtime = BrowserRuntime(
            BrowserStarter { progress ->
                progress.report("downloading", 0.5f)
                seen += runtime.state.value
                progress.report("extracting", null)
                seen += runtime.state.value
                "browser"
            },
            scope,
            Dispatchers.Default,
        )
        withTimeout(WAIT_MS) { runtime.await() }
        assertEquals(listOf(BrowserRuntimeState.Preparing("downloading", 0.5f), BrowserRuntimeState.Preparing("extracting", null)), seen)
    }

    @Test
    fun aFailedStartIsFinalAndCarriesAShortReason() = runBlocking {
        val starts = AtomicInteger()
        val runtime = BrowserRuntime<String>(
            BrowserStarter { starts.incrementAndGet(); throw IOException("Could not download bundle\n\tat somewhere") },
            scope,
            Dispatchers.Default,
        )
        assertNull(withTimeout(WAIT_MS) { runtime.await() })
        val state = assertIs<BrowserRuntimeState.Unavailable>(runtime.state.value)
        assertEquals("Web view unavailable: Could not download bundle", state.reason)

        runtime.ensureStarted()
        assertNull(runtime.await())
        assertEquals(1, starts.get())
    }

    @Test
    fun aMissingNativeLibraryIsUnavailableNotACrash() = runBlocking {
        val runtime = BrowserRuntime<String>(BrowserStarter { throw UnsatisfiedLinkError() }, scope, Dispatchers.Default)
        assertNull(withTimeout(WAIT_MS) { runtime.await() })
        assertEquals("Web view unavailable: UnsatisfiedLinkError", assertIs<BrowserRuntimeState.Unavailable>(runtime.state.value).reason)
    }

    @Test
    fun aDisabledRuntimeNeverStarts() = runBlocking {
        val starts = AtomicInteger()
        val config = JcefConfig(File("bundle"), File("cache"), enabled = false, headless = false)
        val runtime = BrowserRuntime(BrowserStarter { starts.incrementAndGet(); "browser" }, scope, Dispatchers.Default, config.disabledReason)
        assertNull(runtime.await())
        assertEquals(0, starts.get())
        assertTrue(assertIs<BrowserRuntimeState.Unavailable>(runtime.state.value).reason.contains("letta.pluginViews.jcef=false"))
        assertEquals("No display for web views", JcefConfig(File("b"), File("c"), enabled = true, headless = true).disabledReason)
        assertNull(JcefConfig(File("b"), File("c"), enabled = true, headless = false).disabledReason)
    }

    @Test
    fun jcefmavenProgressReadsAsAFractionOrNothing() {
        assertEquals(0.42f, JcefAppStarter.fractionOf(42f))
        assertEquals(1f, JcefAppStarter.fractionOf(140f))
        assertNull(JcefAppStarter.fractionOf(-1f))
        assertEquals("downloading", JcefAppStarter.stageOf(me.friwi.jcefmaven.EnumProgress.DOWNLOADING))
        assertEquals("Downloading the web view 42% (first run)", PluginViewNotices.preparing(BrowserRuntimeState.Preparing("downloading", 0.42f)))
        assertEquals("Starting the web view", PluginViewNotices.preparing(BrowserRuntimeState.Preparing(BrowserRuntime.STAGE_STARTING, null)))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
