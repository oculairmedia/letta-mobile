package com.letta.mobile.startup

import android.app.Application
import com.letta.mobile.util.Telemetry
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppStartupCoordinatorTest {
    private val application = mockk<Application>(relaxed = true)

    @After
    fun tearDown() {
        Telemetry.clear()
    }

    @Test
    fun runStartupTasksRunsTasksInExplicitOrder() = runTest {
        val actions = FakeStartupActions()
        val coordinator = AppStartupCoordinator(actions, StandardTestDispatcher(testScheduler))

        coordinator.runStartupTasks(application)

        assertEquals(ExpectedStartupOrder, actions.calls)
    }

    @Test
    fun runStartupTasksContainsNonRequiredTaskFailuresAndContinues() = runTest {
        val actions = FakeStartupActions(
            failure = StartupFailure("client mode init", IllegalStateException("boom")),
        )
        val coordinator = AppStartupCoordinator(actions, StandardTestDispatcher(testScheduler))

        coordinator.runStartupTasks(application)

        assertEquals(ExpectedStartupOrder, actions.calls)
        assertTrue(
            Telemetry.snapshot().any {
                it.tag == "AppStartup" &&
                    it.name == "client_mode_init:failed" &&
                    it.throwable is IllegalStateException
            },
        )
    }

    @Test
    fun runStartupTasksDoesNotContainCancellation() = runTest {
        val actions = FakeStartupActions(
            failure = StartupFailure("client mode init", CancellationException("cancelled")),
        )
        val coordinator = AppStartupCoordinator(actions, StandardTestDispatcher(testScheduler))

        try {
            coordinator.runStartupTasks(application)
            fail("Expected startup cancellation to be rethrown")
        } catch (_: CancellationException) {
            assertEquals(
                listOf(
                    "notification channel",
                    "automation auth bootstrap",
                    "client mode init",
                ),
                actions.calls,
            )
        }
    }

    private class FakeStartupActions(
        private val failure: StartupFailure? = null,
    ) : AppStartupActions {
        val calls = mutableListOf<String>()

        override suspend fun ensureNotificationChannel() = record("notification channel")

        override suspend fun importPendingAutomationConfig() = record("automation auth bootstrap")

        override suspend fun initializeClientMode() = record("client mode init")

        override suspend fun installProductionJankStats(application: Application) =
            record("production jank monitor")

        override suspend fun installDebugPerformanceMonitor(application: Application) =
            record("debug performance monitor")

        override suspend fun scheduleChannelHeartbeat() = record("channel heartbeat scheduling")

        override suspend fun restoreBotServiceIfConfigured() = record("bot auto-start restore")

        override suspend fun scheduleBotHeartbeat() = record("bot heartbeat scheduling")

        private fun record(name: String) {
            calls += name
            if (name == failure?.taskName) {
                throw failure.throwable
            }
        }
    }

    private data class StartupFailure(
        val taskName: String,
        val throwable: Throwable,
    )

    private companion object {
        val ExpectedStartupOrder = listOf(
            "notification channel",
            "automation auth bootstrap",
            "client mode init",
            "production jank monitor",
            "debug performance monitor",
            "channel heartbeat scheduling",
            "bot auto-start restore",
            "bot heartbeat scheduling",
        )
    }
}
