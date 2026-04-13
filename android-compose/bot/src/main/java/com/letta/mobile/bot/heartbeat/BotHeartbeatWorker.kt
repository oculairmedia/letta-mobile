package com.letta.mobile.bot.heartbeat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class BotHeartbeatWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            BotHeartbeatWorkerEntryPoint::class.java,
        )
        return entryPoint.botHeartbeatSync().run()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BotHeartbeatWorkerEntryPoint {
    fun botHeartbeatSync(): BotHeartbeatSync
}
