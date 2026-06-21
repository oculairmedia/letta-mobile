package com.letta.mobile.channel

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class ChannelHeartbeatSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: ChannelHeartbeatScheduler

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager

        scheduler = ChannelHeartbeatScheduler(context)
    }

    @After
    fun teardown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `schedule enqueues periodic and immediate work`() {
        scheduler.schedule()

        verify {
            workManager.enqueueUniquePeriodicWork(
                eq(ChannelHeartbeatScheduler.PERIODIC_WORK_NAME),
                eq(ExistingPeriodicWorkPolicy.KEEP),
                any()
            )
        }

        verify {
            workManager.enqueueUniqueWork(
                eq(ChannelHeartbeatScheduler.IMMEDIATE_WORK_NAME),
                eq(ExistingWorkPolicy.REPLACE),
                any()
            )
        }
    }
}
