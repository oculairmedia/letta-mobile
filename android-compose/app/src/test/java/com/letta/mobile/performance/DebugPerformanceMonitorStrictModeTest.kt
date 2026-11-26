package com.letta.mobile.performance

import android.app.Application
import android.os.Looper
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

/**
 * Startup installs the debug monitor from a background coroutine worker. Its StrictMode thread
 * policy must land on the main thread, which it exists to guard, and not on that worker, where
 * every file read dispatched to IO is legitimate and would be reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@Tag("integration")
class DebugPerformanceMonitorStrictModeTest {
    @After
    fun resetPolicy() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
    }

    @Test
    fun installFromAWorkerGuardsTheMainThreadNotTheWorker() {
        // Release builds ship a no-op monitor.
        Assume.assumeTrue(ca.oculair.meridian.BuildConfig.DEBUG)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        var workerPolicy: StrictMode.ThreadPolicy? = null
        thread(name = "startup-worker") {
            DebugPerformanceMonitor.install(ApplicationProvider.getApplicationContext())
            workerPolicy = StrictMode.getThreadPolicy()
        }.join()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("the worker keeps a lax policy", StrictMode.ThreadPolicy.LAX.toString(), workerPolicy.toString())
        assertNotEquals("the main thread is guarded", StrictMode.ThreadPolicy.LAX.toString(), StrictMode.getThreadPolicy().toString())
    }
}
