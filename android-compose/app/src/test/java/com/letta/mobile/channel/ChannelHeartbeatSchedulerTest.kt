package com.letta.mobile.channel

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class ChannelHeartbeatSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: ChannelHeartbeatScheduler

    @Before
    fun setup() {
        workManager = mockk(relaxed = true)
        scheduler = ChannelHeartbeatScheduler(workManager)
    }

    @Test
    fun `schedule enqueues periodic and immediate work`() {
        scheduler.schedule()

        verify {
            workManager.enqueueUniquePeriodicWork(
                eq(ChannelHeartbeatScheduler.PERIODIC_WORK_NAME),
                eq(ExistingPeriodicWorkPolicy.KEEP),
                any<PeriodicWorkRequest>()
            )
        }

        verify {
            workManager.enqueueUniqueWork(
                eq(ChannelHeartbeatScheduler.IMMEDIATE_WORK_NAME),
                eq(ExistingWorkPolicy.REPLACE),
                any<OneTimeWorkRequest>()
            )
        }
    }
}
