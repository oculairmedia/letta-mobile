package com.letta.mobile.bot.heartbeat

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.letta.mobile.bot.config.BotConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class BotHeartbeatScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: BotConfigStore,
) {
    suspend fun schedule() = withContext(Dispatchers.IO) {
        val activeConfigs = configStore.getAll().filter { it.enabled }
        val heartbeatConfigs = activeConfigs.filter { it.heartbeatEnabled }
        val scheduledJobs = activeConfigs.flatMap { config ->
            config.scheduledJobs.filter { it.enabled }
        }
        val workManager = WorkManager.getInstance(context)
        if (heartbeatConfigs.isEmpty() && scheduledJobs.isEmpty()) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            return@withContext
        }

        val requiresCharging = heartbeatConfigs.any { it.heartbeatRequiresCharging } ||
            scheduledJobs.any { it.requiresCharging }
        val requiresUnmetered = heartbeatConfigs.any { it.heartbeatRequiresUnmeteredNetwork } ||
            scheduledJobs.any { it.requiresUnmeteredNetwork }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requiresUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(requiresCharging)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<BotHeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val immediateWork = OneTimeWorkRequestBuilder<BotHeartbeatWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork,
        )
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediateWork,
        )
    }

    companion object {
        const val PERIODIC_WORK_NAME = "bot-heartbeat-periodic"
        const val IMMEDIATE_WORK_NAME = "bot-heartbeat-immediate"
    }
}
