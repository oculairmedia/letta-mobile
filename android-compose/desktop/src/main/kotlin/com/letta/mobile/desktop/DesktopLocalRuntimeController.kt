package com.letta.mobile.desktop

import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchPreference
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchSettings
import com.letta.mobile.desktop.runtime.desktopRuntimeHeapMb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bundled runtime's launch settings as the Settings card shows them (letta-mobile-bzvro.5, F05). */
internal data class DesktopRuntimeLaunchState(
    val instanceId: String = "",
    /** The saved preference, or null when the Documents/home fallback applies. */
    val workingDirectory: String? = null,
    val effectiveWorkingDirectory: String = "",
    val heapMb: Int = 0,
    val message: String? = null,
    val isError: Boolean = false,
)

/** What the local-runtime settings need from the outside world; faked in tests. */
internal interface DesktopLocalRuntimeBindings {
    val health: StateFlow<RuntimeHealth>
    fun forceRestart()
    fun currentLaunchState(store: SecureSettingsStore): DesktopRuntimeLaunchState
}

internal object DefaultDesktopLocalRuntimeBindings : DesktopLocalRuntimeBindings {
    override val health: StateFlow<RuntimeHealth> get() = DesktopLocalRuntimeHost.health

    override fun forceRestart() = DesktopLocalRuntimeHost.forceRestart()

    override fun currentLaunchState(store: SecureSettingsStore): DesktopRuntimeLaunchState {
        val context = DesktopRuntimeLaunchPreference.currentContext()
        val heap = context.environment["NODE_OPTIONS"].orEmpty()
            .substringAfter("--max-old-space-size=", "")
            .takeWhile(Char::isDigit)
            .toIntOrNull()
            ?: desktopRuntimeHeapMb(null)
        return DesktopRuntimeLaunchState(
            instanceId = DesktopRuntimeLaunchSettings.loadOrCreateInstanceId(store),
            workingDirectory = DesktopRuntimeLaunchSettings.readWorkingDirectory(store),
            effectiveWorkingDirectory = context.workingDirectory?.absolutePath.orEmpty(),
            heapMb = heap,
        )
    }
}

/**
 * State and actions for the "Local runtime" settings card: the crash supervisor's health with
 * Force restart (F03) and the launch settings (F05). Changing the working directory restarts the
 * runtime through [onRestartRequested] when the local runtime is the active backend.
 */
internal class DesktopLocalRuntimeController(
    private val store: SecureSettingsStore,
    private val scope: CoroutineScope,
    private val onRestartRequested: () -> Unit,
    private val bindings: DesktopLocalRuntimeBindings = DefaultDesktopLocalRuntimeBindings,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val health: StateFlow<RuntimeHealth> get() = bindings.health

    private val _launch = MutableStateFlow(DesktopRuntimeLaunchState())
    val launch: StateFlow<DesktopRuntimeLaunchState> = _launch.asStateFlow()

    fun load() {
        scope.launch {
            val loaded = withContext(io) { bindings.currentLaunchState(store) }
            _launch.update { loaded.copy(message = it.message, isError = it.isError) }
        }
    }

    fun forceRestart() = bindings.forceRestart()

    fun changeWorkingDirectory(path: String) {
        scope.launch {
            val error = withContext(io) { DesktopRuntimeLaunchSettings.saveWorkingDirectory(store, path) }
            if (error != null) {
                _launch.update { it.copy(message = error, isError = true) }
                return@launch
            }
            reloadAfterChange("Saved. Restarting the bundled runtime…")
        }
    }

    fun resetWorkingDirectory() {
        scope.launch {
            withContext(io) { DesktopRuntimeLaunchSettings.resetWorkingDirectory(store) }
            reloadAfterChange("Reset to default. Restarting the bundled runtime…")
        }
    }

    private suspend fun reloadAfterChange(message: String) {
        val loaded = withContext(io) { bindings.currentLaunchState(store) }
        _launch.value = loaded.copy(message = message, isError = false)
        onRestartRequested()
    }
}

/** One-line description of the runtime's health for the card and the chat banner. */
internal fun RuntimeHealth.describe(): String = when {
    gaveUp -> "Stopped after $consecutiveCrashes crashes in a minute. Automatic restart is off until you restart it."
    restartPending -> "Crashed (exit ${lastExitCode ?: "?"}). Restarting…"
    active -> if (consecutiveCrashes > 0) "Running (recovered from $consecutiveCrashes recent crash${if (consecutiveCrashes == 1) "" else "es"})." else "Running."
    stoppedUnexpectedly -> "Stopped unexpectedly (exit ${lastExitCode ?: "?"})."
    else -> "Not running. It starts when you connect to the local runtime."
}
