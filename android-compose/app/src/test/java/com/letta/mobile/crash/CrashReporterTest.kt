package com.letta.mobile.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import org.junit.jupiter.api.Tag

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class CrashReporterTest {
    private lateinit var context: Context
    private lateinit var reporter: CrashReporter
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        crashFile().parentFile?.deleteRecursively()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        reporter = CrashReporter(context)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        crashFile().parentFile?.deleteRecursively()
    }

    @Test
    fun `install with no prior crash exposes null lastCrash`() {
        reporter.install()
        assertNull(reporter.lastCrash.value)
    }

    @Test
    fun `handler persists crash summary and chains previous handler`() {
        // Arrange: install a prior handler we can observe, then CrashReporter on top.
        var chainedThrowable: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, t -> chainedThrowable = t }
        reporter.install()

        val thrown = IllegalStateException("boom")

        // Act: simulate uncaught exception by invoking the installed handler directly.
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), thrown)

        // Assert: file written + previous handler invoked.
        assertTrue("Crash file should exist after uncaught exception", crashFile().exists())
        val json = JSONObject(crashFile().readText())
        assertEquals(IllegalStateException::class.java.name, json.getString("type"))
        assertEquals("boom", json.getString("message"))
        assertTrue(json.getString("stackHead").contains("IllegalStateException"))
        assertEquals(1, json.getInt("schemaVersion"))
        assertNotNull("Previous handler must be chained", chainedThrowable)
        assertEquals(thrown, chainedThrowable)
    }

    @Test
    fun `install loads persisted crash from disk into stateflow`() {
        // Seed an on-disk record as if the previous session had crashed.
        val file = crashFile()
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("timestamp", 12345L)
                .put("threadName", "main")
                .put("type", "java.lang.IllegalStateException")
                .put("message", "prior boom")
                .put("stackHead", "at com.letta.mobile.Foo.bar")
                .put("sentryEventId", "abc-123")
                .put("schemaVersion", 1)
                .toString()
        )

        reporter.install()

        val loaded = reporter.lastCrash.value
        assertNotNull(loaded)
        assertEquals("prior boom", loaded!!.message)
        assertEquals("abc-123", loaded.sentryEventId)
    }

    @Test
    fun `dismiss clears stateflow and deletes file`() {
        val file = crashFile()
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("timestamp", 1L)
                .put("threadName", "t")
                .put("type", "X")
                .put("message", "m")
                .put("stackHead", "s")
                .put("sentryEventId", JSONObject.NULL)
                .put("schemaVersion", 1)
                .toString()
        )
        reporter.install()
        assertNotNull(reporter.lastCrash.value)

        reporter.dismiss()

        assertNull(reporter.lastCrash.value)
        assertFalse(file.exists())
    }

    @Test
    fun `corrupt on-disk record is discarded on install`() {
        val file = crashFile()
        file.parentFile?.mkdirs()
        file.writeText("not json {")

        reporter.install()

        assertNull(reporter.lastCrash.value)
        assertFalse(file.exists())
    }

    private fun crashFile(): File =
        File(File(context.filesDir, "crash"), "last.json")
}
