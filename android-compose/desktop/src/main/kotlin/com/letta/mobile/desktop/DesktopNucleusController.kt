package com.letta.mobile.desktop

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.nativehttp.NativeHttpClient
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.notification
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
    val system: DesktopSystemSnapshot = DesktopSystemSnapshot(),
    val justUpdatedMessage: String? = null,
)

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
        }
    }
    private val mutableState = MutableStateFlow(DesktopNucleusState())
    val state: StateFlow<DesktopNucleusState> = mutableState.asStateFlow()

    private var availableUpdate: UpdateInfo? = null
    private var downloadedInstaller: File? = null

    init {
        scope.launch(Dispatchers.IO) {
            AutoLaunch.preload()
            NotificationManager.initialize()
            val updateEvent = updater.consumeUpdateEvent()
            mutableState.update {
                it.copy(
                    autoLaunchState = AutoLaunch.state(),
                    notificationsAvailable = NotificationManager.isAvailable(),
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

    fun sendTestNotification(onActivated: () -> Unit) {
        notification(
            title = "Letta Desktop",
            message = "Native notifications are connected.",
            onActivated = onActivated,
        ).send()
    }

    fun notifyAgentFinished(agentName: String, onActivated: () -> Unit) {
        notification(
            title = agentName,
            message = "Your agent finished responding.",
            onActivated = onActivated,
        ).send()
    }

    fun notifyFailure(message: String, onActivated: () -> Unit) {
        notification(
            title = "Letta Desktop needs attention",
            message = message,
            onActivated = onActivated,
        ).send()
    }
}
