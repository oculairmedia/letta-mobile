package com.letta.mobile

import com.letta.mobile.bot.heartbeat.BotHeartbeatScheduler
import com.letta.mobile.bot.service.BotServiceAutoStarter
import com.letta.mobile.channel.ChannelHeartbeatScheduler
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.performance.DebugPerformanceMonitor
import com.letta.mobile.util.Telemetry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class LettaApplicationTest {
    private lateinit var application: LettaApplication

    @Before
    fun setUp() {
        Telemetry.clear()
        mockkObject(DebugPerformanceMonitor)

        application = LettaApplication().apply {
            crashReporter = mockk(relaxed = true)
            channelNotificationPublisher = mockk(relaxed = true)
            channelHeartbeatScheduler = mockk(relaxed = true)
            botServiceAutoStarter = mockk(relaxed = true)
            botHeartbeatScheduler = mockk(relaxed = true)
        }

        every { DebugPerformanceMonitor.install(any()) } just runs
        every { application.crashReporter.install() } just runs
        every { application.channelNotificationPublisher.ensureChannel() } just runs
        every { application.channelHeartbeatScheduler.schedule() } just runs
        coEvery { application.botServiceAutoStarter.restoreIfConfigured() } returns false
        coEvery { application.botHeartbeatScheduler.schedule() } returns Unit
    }

    @After
    fun tearDown() {
        Telemetry.clear()
        unmockkObject(DebugPerformanceMonitor)
    }

    @Test
    fun `runStartupHooks emits onCreate start and end even in Robolectric mode`() {
        application.runStartupHooks(isRobolectricRuntime = true)

        val events = Telemetry.snapshot()
        assertTrue(events.any { it.tag == "App" && it.name == "onCreate.start" })
        assertTrue(events.any { it.tag == "App" && it.name == "onCreate.end" })
        assertTrue(events.none { it.tag == "App" && it.name == "init.failed" })
    }

    @Test
    fun `runStartupHooks records init failures for startup components`() {
        val perfFailure = IllegalStateException("perf boom")
        val heartbeatFailure = IllegalStateException("channel boom")
        val autoStartFailure = IllegalStateException("autostart boom")
        val botHeartbeatFailure = IllegalStateException("bot heartbeat boom")

        every { DebugPerformanceMonitor.install(any()) } throws perfFailure
        every { application.channelHeartbeatScheduler.schedule() } throws heartbeatFailure
        coEvery { application.botServiceAutoStarter.restoreIfConfigured() } throws autoStartFailure
        coEvery { application.botHeartbeatScheduler.schedule() } throws botHeartbeatFailure

        application.runStartupHooks(
            isRobolectricRuntime = false,
            runAsyncInline = true,
        )

        val failuresByComponent = Telemetry.snapshot()
            .filter { it.tag == "App" && it.name == "init.failed" }
            .associateBy { it.attrs["component"] }

        assertEquals(setOf("perfMonitor", "channelHeartbeat", "botAutostart", "botHeartbeat"), failuresByComponent.keys)
        assertEquals("IllegalStateException", failuresByComponent["perfMonitor"]?.attrs?.get("errorClass"))
        assertEquals("IllegalStateException", failuresByComponent["channelHeartbeat"]?.attrs?.get("errorClass"))
        assertEquals("IllegalStateException", failuresByComponent["botAutostart"]?.attrs?.get("errorClass"))
        assertEquals("IllegalStateException", failuresByComponent["botHeartbeat"]?.attrs?.get("errorClass"))
        assertTrue(Telemetry.snapshot().any { it.tag == "App" && it.name == "onCreate.end" })
    }

    @Test
    fun `onTerminate emits telemetry`() {
        application.onTerminate()

        assertTrue(Telemetry.snapshot().any { it.tag == "App" && it.name == "onTerminate" })
    }
}
