package com.letta.mobile.startup

import android.app.Application
import android.util.Log
import com.letta.mobile.di.DefaultDispatcher
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// The scope is process-lifetime by design: startup work runs once per process and ends with it.
@Suppress("NoDetachedCoroutineLifecycle")
@Singleton
class AppStartupCoordinator @Inject constructor(
    private val actions: AppStartupActions,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun start(application: Application) {
        scope.launch {
            runStartupTasks(application)
        }
    }

    /**
     * Runs the startup work; [start] calls this on the background dispatcher.
     *
     * [criticalPathWarmups] start first and run concurrently with the ordered
     * [startupTasks]: they build singletons the first Activity frame needs, so they
     * must not queue behind unrelated work (letta-mobile-wyo3p). `UNDISPATCHED`
     * makes each warm-up begin before the ordered tasks; it only runs until the
     * warm-up's first suspension (its switch to the IO dispatcher).
     */
    suspend fun runStartupTasks(application: Application): Unit = coroutineScope {
        criticalPathWarmups().forEach { task ->
            launch(start = CoroutineStart.UNDISPATCHED) { runTask(task) }
        }
        startupTasks(application).forEach { task ->
            runTask(task)
        }
    }

    private fun criticalPathWarmups(): List<AppStartupTask> = listOf(
        AppStartupTask("prewarm settings") {
            actions.prewarmSettings()
        },
    )

    private fun startupTasks(application: Application): List<AppStartupTask> = listOf(
        AppStartupTask("prewarm database") {
            actions.prewarmDatabase()
        },
        AppStartupTask("notification channel") {
            actions.ensureNotificationChannel()
        },
        AppStartupTask("automation auth bootstrap") {
            actions.importPendingAutomationConfig()
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
