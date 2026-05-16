package com.letta.mobile.startup

import android.app.Application
import android.util.Log
import com.letta.mobile.di.DefaultDispatcher
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AppStartupCoordinator @Inject constructor(
    private val actions: AppStartupActions,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun start(application: Application) {
        scope.launch {
            runStartupTasks(application)
        }
    }

    internal suspend fun runStartupTasks(application: Application) {
        startupTasks(application).forEach { task ->
            runTask(task)
        }
    }

    private fun startupTasks(application: Application): List<AppStartupTask> = listOf(
        AppStartupTask("notification channel") {
            actions.ensureNotificationChannel()
        },
        AppStartupTask("automation auth bootstrap") {
            actions.importPendingAutomationConfig()
        },
        AppStartupTask("client mode init") {
            actions.initializeClientMode()
        },
        AppStartupTask("production jank monitor") {
            actions.installProductionJankStats(application)
        },
        AppStartupTask("debug performance monitor") {
            actions.installDebugPerformanceMonitor(application)
        },
        AppStartupTask("channel heartbeat scheduling") {
            actions.scheduleChannelHeartbeat()
        },
        AppStartupTask("bot auto-start restore") {
            actions.restoreBotServiceIfConfigured()
        },
        AppStartupTask("bot heartbeat scheduling") {
            actions.scheduleBotHeartbeat()
        },
    )

    private suspend fun runTask(task: AppStartupTask) {
        runCatching {
            task.run()
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            Log.w(TAG, "Skipping ${task.name}", error)
            Telemetry.error(TAG, "${task.telemetryName}:failed", error)
            if (task.failurePolicy == AppStartupFailurePolicy.Required) {
                throw error
            }
        }
    }

    private companion object {
        private const val TAG = "AppStartup"
    }
}

private enum class AppStartupFailurePolicy {
    Continue,
    Required,
}

private class AppStartupTask(
    val name: String,
    val failurePolicy: AppStartupFailurePolicy = AppStartupFailurePolicy.Continue,
    val run: suspend () -> Unit,
) {
    val telemetryName: String = name.replace(' ', '_')
}
