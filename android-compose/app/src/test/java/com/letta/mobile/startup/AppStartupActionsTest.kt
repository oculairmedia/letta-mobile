package com.letta.mobile.startup

import android.app.Application
import android.content.Context
import com.letta.mobile.channel.ChannelHeartbeatScheduler
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.debug.AutomationAuthBootstrap
import com.letta.mobile.performance.DebugPerformanceMonitor
import com.letta.mobile.performance.ProductionJankStatsMonitor
import dagger.Lazy
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AppStartupActionsTest {
    private val context: Context = mockk()
    private val channelNotificationPublisher: ChannelNotificationPublisher = mockk(relaxed = true)
    private val settingsRepository: ISettingsRepository = mockk()
    private val lazySettingsRepository: Lazy<ISettingsRepository> = mockk()
    private val channelHeartbeatScheduler: ChannelHeartbeatScheduler = mockk(relaxed = true)
    private val application: Application = mockk()

    private lateinit var startupActions: DefaultAppStartupActions

    @Before
    fun setUp() {
        every { lazySettingsRepository.get() } returns settingsRepository
        mockkObject(AutomationAuthBootstrap)
        mockkObject(ProductionJankStatsMonitor)
        mockkObject(DebugPerformanceMonitor)

        startupActions = DefaultAppStartupActions(
            context = context,
            channelNotificationPublisher = channelNotificationPublisher,
            settingsRepository = lazySettingsRepository,
            channelHeartbeatScheduler = channelHeartbeatScheduler
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `ensureNotificationChannel calls channelNotificationPublisher`() = runTest {
        startupActions.ensureNotificationChannel()

        coVerify { channelNotificationPublisher.ensureChannel() }
    }

    @Test
    fun `importPendingAutomationConfig calls AutomationAuthBootstrap`() = runTest {
        every { AutomationAuthBootstrap.importPendingConfig(any(), any<ISettingsRepository>()) } returns Unit

        startupActions.importPendingAutomationConfig()

        verify { AutomationAuthBootstrap.importPendingConfig(context, settingsRepository) }
    }

    @Test
    fun `installProductionJankStats calls ProductionJankStatsMonitor`() = runTest {
        every { ProductionJankStatsMonitor.install(any()) } returns Unit

        startupActions.installProductionJankStats(application)

        verify { ProductionJankStatsMonitor.install(application) }
    }

    @Test
    fun `installDebugPerformanceMonitor calls DebugPerformanceMonitor`() = runTest {
        every { DebugPerformanceMonitor.install(any()) } returns Unit

        startupActions.installDebugPerformanceMonitor(application)

        verify { DebugPerformanceMonitor.install(application) }
    }

    @Test
    fun `scheduleChannelHeartbeat calls channelHeartbeatScheduler`() = runTest {
        startupActions.scheduleChannelHeartbeat()

        verify { channelHeartbeatScheduler.schedule() }
    }
}
