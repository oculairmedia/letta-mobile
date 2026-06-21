package com.letta.mobile.channel

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelHeartbeatScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun schedule() {
        val periodicConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            // Periodic background sync should yield on critically low battery.
            .setRequiresBatteryNotLow(true)
            .build()

        val immediateConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<ChannelHeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(periodicConstraints)
            .build()

        val immediateWork = OneTimeWorkRequestBuilder<ChannelHeartbeatWorker>()
            .setConstraints(immediateConstraints)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork,
        )
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediateWork,
        )
    }

    companion object {
        const val PERIODIC_WORK_NAME = "channel-heartbeat-periodic"
        const val IMMEDIATE_WORK_NAME = "channel-heartbeat-immediate"
    }
}
