package com.letta.mobile.startup

import android.app.Application
import android.content.Context
import com.letta.mobile.channel.ChannelHeartbeatScheduler
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.debug.AutomationAuthBootstrap
import com.letta.mobile.performance.DebugPerformanceMonitor
import com.letta.mobile.performance.ProductionJankStatsMonitor
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface AppStartupActions {
    suspend fun ensureNotificationChannel()
    suspend fun importPendingAutomationConfig()
    suspend fun installProductionJankStats(application: Application)
    suspend fun installDebugPerformanceMonitor(application: Application)
    suspend fun scheduleChannelHeartbeat()
    suspend fun prewarmDatabase()

    /**
     * Builds the settings repository (and, transitively, the Keystore-backed
     * EncryptedSharedPreferences behind it) off the main thread so the first
     * composition finds the singleton already constructed (letta-mobile-wyo3p).
     */
    suspend fun prewarmSettings()
}

@Singleton
class DefaultAppStartupActions @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channelNotificationPublisher: ChannelNotificationPublisher,
    private val settingsRepository: Lazy<ISettingsRepository>,
    private val channelHeartbeatScheduler: ChannelHeartbeatScheduler,
    private val database: Lazy<LettaDatabase>,
) : AppStartupActions {
    override suspend fun ensureNotificationChannel() {
        channelNotificationPublisher.ensureChannel()
    }

    override suspend fun importPendingAutomationConfig() {
        AutomationAuthBootstrap.importPendingConfig(context, settingsRepository.get())
    }

    override suspend fun installProductionJankStats(application: Application) {
        ProductionJankStatsMonitor.install(application)
    }

    override suspend fun installDebugPerformanceMonitor(application: Application) {
        DebugPerformanceMonitor.install(application)
    }

    override suspend fun scheduleChannelHeartbeat() {
        channelHeartbeatScheduler.schedule()
    }

    override suspend fun prewarmSettings() {
        // letta-mobile-wyo3p: MasterKey + EncryptedSharedPreferences creation costs
        // ~100-200 ms (Keystore binder + Tink init). MainActivity used to build it
        // synchronously during injection; resolving it here on IO lets the Activity
        // reach first composition in parallel.
        withContext(Dispatchers.IO) {
            settingsRepository.get()
        }
    }

    override suspend fun prewarmDatabase() {
        // letta-mobile-g2ff0: Room DB init is expensive (~700ms). Pre-warm it on IO
        // so the first navigation transition doesn't pay the cost synchronously.
        withContext(Dispatchers.IO) {
            database.get()
        }
    }
}
