package com.letta.mobile.desktop

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.nativehttp.NativeHttpClient
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import dev.nucleusframework.notification.windows.ShortcutPolicy
import dev.nucleusframework.notification.windows.WindowsNotificationCenter
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.SystemInfo
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.provider.GitHubProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal enum class DesktopUpdatePhase {
    Idle,
    Checking,
    Available,
    Downloading,
    ReadyToInstall,
    UpToDate,
    Unsupported,
    Failed,
}

internal data class DesktopSystemSnapshot(
    val available: Boolean = false,
    val cpuUsagePercent: Float? = null,
    val physicalCores: Int? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    val gpuName: String? = null,
    val gpuUsagePercent: Float? = null,
    val gpuTemperatureCelsius: Float? = null,
    val cpuTemperatureCelsius: Float? = null,
    val receivedBytes: Long = 0,
    val transmittedBytes: Long = 0,
    val connected: Boolean? = null,
)

internal data class DesktopNucleusState(
    val updatePhase: DesktopUpdatePhase = DesktopUpdatePhase.Idle,
    val updateVersion: String? = null,
    val updateProgressPercent: Int = 0,
    val updateMessage: String? = null,
    val autoLaunchState: AutoLaunchState = AutoLaunchState.UNSUPPORTED,
    val notificationsAvailable: Boolean = false,
    val notificationMessage: String? = null,
    val system: DesktopSystemSnapshot = DesktopSystemSnapshot(),
    val justUpdatedMessage: String? = null,
)

/**
 * Mirrors the release workflow's updater-channel derivation: alpha/beta keep
 * their own channel files, labels Nucleus doesn't serve (rc, dev, ...) map to
 * beta, and stable versions poll latest.
 */
internal fun updateChannelFor(version: String): String {
    val prerelease = version.substringAfter('-', missingDelimiterValue = "")
    if (prerelease.isEmpty()) return "latest"
    val label = prerelease.lowercase().takeWhile { it in 'a'..'z' }
    return if (label == "alpha") "alpha" else "beta"
}

/**
 * Owns Nucleus runtime state outside composition. The UI renders [state] and
 * forwards actions; native calls and update/network work stay in this class.
 */
internal class DesktopNucleusController(
    private val scope: CoroutineScope,
) {
    private val updater by lazy {
        NucleusUpdater {
            provider = GitHubProvider(owner = "oculairmedia", repo = "letta-mobile")
            httpClient = NativeHttpClient.create()
            // Nucleus defaults to the "latest" channel, but the release
            // workflow publishes pre-release metadata to beta.yml/alpha.yml —
            // a pre-release install must poll its own channel file or it never
            // sees follow-up pre-releases. currentVersion is auto-detected
            // from the packaged app before this block runs.
            channel = updateChannelFor(currentVersion)
        }
    }
    private val mutableState = MutableStateFlow(DesktopNucleusState())
    val state: StateFlow<DesktopNucleusState> = mutableState.asStateFlow()

    private var availableUpdate: UpdateInfo? = null
    private var downloadedInstaller: File? = null

    init {
        scope.launch(Dispatchers.IO) {
            AutoLaunch.preload()
            val notificationsInitialized = initializeNotifications()
            val updateEvent = updater.consumeUpdateEvent()
            mutableState.update {
                it.copy(
                    autoLaunchState = AutoLaunch.state(),
                    notificationsAvailable = notificationsInitialized,
                    notificationMessage = if (notificationsInitialized) {
                        null
                    } else {
                        "Windows could not initialize the native notification identity."
                    },
                    justUpdatedMessage = updateEvent?.let { event ->
                        "Updated from ${event.previousVersion} to ${event.newVersion}"
                    },
                )
            }
            refreshSystemInfo()
        }
    }

    fun checkForUpdates() {
        if (!updater.isUpdateSupported()) {
            mutableState.update {
                it.copy(
                    updatePhase = DesktopUpdatePhase.Unsupported,
                    updateMessage = "Updates are managed externally for this build type.",
                )
            }
            return
        }
        mutableState.update { it.copy(updatePhase = DesktopUpdatePhase.Checking, updateMessage = null) }
        scope.launch {
            when (val result = updater.checkForUpdates()) {
                is UpdateResult.Available -> {
                    availableUpdate = result.info
                    mutableState.update {
                        it.copy(
                            updatePhase = DesktopUpdatePhase.Available,
                            updateVersion = result.info.version,
                            updateMessage = "Version ${result.info.version} is available.",
                        )
                    }
                }
                UpdateResult.NotAvailable -> mutableState.update {
                    it.copy(updatePhase = DesktopUpdatePhase.UpToDate, updateMessage = "Letta Desktop is up to date.")
                }
                is UpdateResult.Error -> mutableState.update {
                    it.copy(
                        updatePhase = DesktopUpdatePhase.Failed,
                        updateMessage = result.exception.message ?: "Update check failed.",
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val info = availableUpdate ?: return
        mutableState.update {
            it.copy(updatePhase = DesktopUpdatePhase.Downloading, updateProgressPercent = 0, updateMessage = null)
        }
        scope.launch {
            runCatching {
                updater.downloadUpdate(info).collect { progress ->
                    progress.file?.let { downloadedInstaller = it }
                    mutableState.update { state ->
                        state.copy(updateProgressPercent = progress.percent.toInt().coerceIn(0, 100))
                    }
                }
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        updatePhase = DesktopUpdatePhase.ReadyToInstall,
                        updateProgressPercent = 100,
                        updateMessage = "Download verified with SHA-512 and ready to install.",
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(updatePhase = DesktopUpdatePhase.Failed, updateMessage = error.message ?: "Download failed.")
                }
            }
        }
    }

    fun installUpdateAndRestart() {
        downloadedInstaller?.let(updater::installAndRestart)
    }

    fun setAutoLaunch(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (enabled) AutoLaunch.enable() else AutoLaunch.disable()
            mutableState.update { it.copy(autoLaunchState = AutoLaunch.state()) }
        }
    }

    fun openAutoLaunchSettings() {
        AutoLaunch.openSystemSettings()
    }

    fun refreshSystemInfo() {
        scope.launch(Dispatchers.IO) {
            val cpu = SystemInfo.cpuInfo()
            val memory = SystemInfo.memoryInfo()
            val gpu = SystemInfo.gpus().firstOrNull()
            val components = SystemInfo.components()
            val cpuTemperature = components
                .filter { it.label.contains("cpu", ignoreCase = true) }
                .mapNotNull { it.temperature }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
            val networks = SystemInfo.networks()
            mutableState.update {
                it.copy(
                    system = DesktopSystemSnapshot(
                        available = SystemInfo.isAvailable(),
                        cpuUsagePercent = cpu?.globalCpuUsage,
                        physicalCores = cpu?.physicalCoreCount,
                        memoryUsedBytes = memory?.usedMemory,
                        memoryTotalBytes = memory?.totalMemory,
                        gpuName = gpu?.name,
                        gpuUsagePercent = gpu?.gpuUsage,
                        gpuTemperatureCelsius = gpu?.temperature,
                        cpuTemperatureCelsius = cpuTemperature,
                        receivedBytes = networks.sumOf { network -> network.receivedBytes },
                        transmittedBytes = networks.sumOf { network -> network.transmittedBytes },
                        connected = SystemInfo.connectivityInfo()?.isConnected,
                    ),
                )
            }
        }
    }

    // Native notification sends go through OS IPC; keep them off the
    // Compose/Swing main thread like the other native calls in this class.
    fun sendTestNotification(onActivated: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = notification(
                title = "Letta Desktop",
                message = "Native notifications are connected.",
                onActivated = onActivated,
                onFailed = {
                    mutableState.update {
                        it.copy(notificationMessage = "Windows rejected the test notification.")
                    }
                },
            ).send()
            mutableState.update {
                it.copy(
                    notificationMessage = when (result) {
                        is NotificationResult.Success -> "Test notification sent to Windows."
                        is NotificationResult.Failure -> "Notification failed: ${result.reason}"
                    },
                )
            }
        }
    }

    fun notifyAgentFinished(agentName: String, onActivated: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            notification(
                title = agentName,
                message = "Your agent finished responding.",
                onActivated = onActivated,
            ).send()
        }
    }

    fun notifyFailure(message: String, onActivated: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            notification(
                title = "Letta Desktop needs attention",
                message = message,
                onActivated = onActivated,
            ).send()
        }
    }

    private fun initializeNotifications(): Boolean {
        val initialized = if (Platform.Current == Platform.Windows) {
            WindowsNotificationCenter.initialize(
                aumid = LETTA_WINDOWS_AUMID,
                appName = LETTA_DESKTOP_APP_NAME,
                shortcutPolicy = ShortcutPolicy.REQUIRE_CREATE,
            )
        } else {
            NotificationManager.isAvailable()
        }
        // Marks the common dispatcher initialized after the Windows backend has
        // established the explicit AUMID and Start Menu shortcut.
        NotificationManager.initialize()
        return initialized && NotificationManager.isAvailable()
    }
}
