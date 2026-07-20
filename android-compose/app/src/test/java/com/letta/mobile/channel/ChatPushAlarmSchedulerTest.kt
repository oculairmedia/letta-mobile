package com.letta.mobile.channel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.letta.mobile.util.Telemetry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatPushAlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var appContext: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var pendingIntent: PendingIntent

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        alarmManager = mockk(relaxed = true)
        pendingIntent = mockk(relaxed = true)

        every { context.applicationContext } returns appContext
        every { appContext.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { context.packageName } returns "com.letta.mobile"
        every { appContext.packageName } returns "com.letta.mobile"

        mockkStatic(PendingIntent::class)
        every {
            PendingIntent.getBroadcast(any(), any(), any(), any())
        } returns pendingIntent

        Telemetry.clear()
    }

    @After
    fun tearDown() {
        unmockkStatic(PendingIntent::class)
        Telemetry.clear()
    }

    @Test
    fun `schedule skipped when alarm manager is null`() {
        every { appContext.getSystemService(Context.ALARM_SERVICE) } returns null

        ChatPushAlarmScheduler.schedule(context)

        val events = Telemetry.snapshot()
        val event = events.firstOrNull { it.tag == "ChatPushAlarm" && it.name == "scheduleSkipped" }
        assertNotNull(event)
        assertEquals("missingAlarmManager", event!!.attrs["reason"])
    }

    @Test
    fun `schedule skipped when pending intent is null`() {
        every {
            PendingIntent.getBroadcast(any(), any(), any(), any())
        } returns null

        ChatPushAlarmScheduler.schedule(context)

        val events = Telemetry.snapshot()
        val event = events.firstOrNull { it.tag == "ChatPushAlarm" && it.name == "scheduleSkipped" }
        assertNotNull(event)
        assertEquals("missingPendingIntent", event!!.attrs["reason"])
    }

    @Test
    fun `schedule uses setAndAllowWhileIdle`() {
        // minSdk is 26 (M+), so the pre-M AlarmManager.set branch is gone.
        ChatPushAlarmScheduler.schedule(context)

        verify {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                any(),
                pendingIntent
            )
        }

        val events = Telemetry.snapshot()
        val event = events.firstOrNull { it.tag == "ChatPushAlarm" && it.name == "scheduled" }
        assertNotNull(event)
        assertEquals(15L, event!!.attrs["intervalMinutes"])
    }

    @Test
    fun `schedule logs error when setAndAllowWhileIdle throws`() {
        val exception = RuntimeException("AlarmManager crashed")
        every {
            alarmManager.setAndAllowWhileIdle(any(), any(), any())
        } throws exception

        ChatPushAlarmScheduler.schedule(context)

        val events = Telemetry.snapshot()
        val errorEvent = events.firstOrNull { it.tag == "ChatPushAlarm" && it.name == "scheduleFailed" }
        assertNotNull(errorEvent)
        assertEquals(exception, errorEvent!!.throwable)
    }

    @Test
    fun `cancel successfully cancels alarm and pending intent`() {
        ChatPushAlarmScheduler.cancel(context)

        verify { alarmManager.cancel(pendingIntent) }
        verify { pendingIntent.cancel() }

        val events = Telemetry.snapshot()
        val event = events.firstOrNull { it.tag == "ChatPushAlarm" && it.name == "cancelled" }
        assertNotNull(event)
    }

    @Test
    fun `cancel safely does nothing if pending intent is null`() {
        every {
            PendingIntent.getBroadcast(any(), any(), any(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        } returns null

        ChatPushAlarmScheduler.cancel(context)

        verify(exactly = 0) { alarmManager.cancel(any<PendingIntent>()) }

        val events = Telemetry.snapshot()
        val event = events.firstOrNull { it.tag == "ChatPushAlarm" && it.name == "cancelled" }
        assertNotNull(event)
    }

    @Test
    fun `cancel logs error when alarm manager cancel throws`() {
        val exception = RuntimeException("Cancel failed")
        every { alarmManager.cancel(any<PendingIntent>()) } throws exception

        ChatPushAlarmScheduler.cancel(context)

        val events = Telemetry.snapshot()
        val errorEvent = events.firstOrNull { it.tag == "ChatPushAlarm" && it.name == "cancelFailed" }
        assertNotNull(errorEvent)
        assertEquals(exception, errorEvent!!.throwable)
    }
}
