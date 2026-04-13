package com.letta.mobile.bot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.letta.mobile.bot.api.BotApiServer
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.GatewayStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android foreground service that keeps the bot gateway alive.
 *
 * When the bot is running in LOCAL mode, this service ensures the bot sessions
 * continue processing messages even when the app is in the background.
 * It displays a persistent notification showing the bot status.
 */
@AndroidEntryPoint
class BotForegroundService : Service() {

    @Inject lateinit var gateway: BotGateway
    @Inject lateinit var configStore: BotConfigStore
    @Inject lateinit var apiServer: BotApiServer

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var gatewayMonitorJob: kotlinx.coroutines.Job? = null
    private var startJob: kotlinx.coroutines.Job? = null
    private var currentApiPort: Int? = null
    private var currentApiAuthRequired = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBot()
            ACTION_STOP -> stopBot()
            else -> startBot()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        gatewayMonitorJob?.cancel()
        startJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startBot() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        if (startJob?.isActive == true) {
            Log.i(TAG, "Bot start already in progress; ignoring duplicate request")
            return
        }

        startJob = scope.launch {
            try {
                val configs = configStore.configs.first()
                val enabledConfigs = configs.filter { it.enabled }

                if (enabledConfigs.isEmpty()) {
                    Log.w(TAG, "No enabled bot configs found")
                    updateNotification("No bots configured")
                    return@launch
                }

                val apiConfig = enabledConfigs.firstOrNull { it.apiServerEnabled }
                if (apiConfig != null) {
                    apiServer.start(apiConfig.apiServerPort, apiConfig.apiServerToken)
                    currentApiPort = apiConfig.apiServerPort
                    currentApiAuthRequired = !apiConfig.apiServerToken.isNullOrBlank()
                } else if (apiServer.isRunning) {
                    apiServer.stop()
                    currentApiPort = null
                    currentApiAuthRequired = false
                }

                gateway.start(enabledConfigs)

                gatewayMonitorJob?.cancel()
                gatewayMonitorJob = scope.launch {
                    gateway.status.collectLatest { status ->
                        val text = when (status) {
                            GatewayStatus.STARTING -> "Starting bot sessions..."
                            GatewayStatus.RUNNING -> {
                                val count = gateway.sessions.value.size
                                val apiSuffix = currentApiPort?.let {
                                    " · API $it${if (currentApiAuthRequired) " auth" else ""}"
                                } ?: ""
                                "$count bot${if (count != 1) "s" else ""} running$apiSuffix"
                            }
                            GatewayStatus.STOPPING -> "Stopping..."
                            GatewayStatus.STOPPED -> "Stopped"
                            GatewayStatus.ERROR -> "Error — check logs"
                        }
                        updateNotification(text)

                        if (status == GatewayStatus.STOPPED) {
                            stopSelf()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start bot", e)
                updateNotification("Error: ${e.message}")
            }
        }
    }

    private fun stopBot() {
        scope.launch {
            gatewayMonitorJob?.cancel()
            apiServer.stop()
            currentApiPort = null
            currentApiAuthRequired = false
            gateway.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Letta Bot")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bot Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the Letta bot running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "BotForegroundService"
        private const val CHANNEL_ID = "letta_bot_service"
        private const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.letta.mobile.bot.START"
        const val ACTION_STOP = "com.letta.mobile.bot.STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, BotForegroundService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, BotForegroundService::class.java).apply { action = ACTION_STOP }
    }
}
