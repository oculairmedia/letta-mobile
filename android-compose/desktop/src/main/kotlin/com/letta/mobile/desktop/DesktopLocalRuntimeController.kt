package com.letta.mobile.desktop

import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.runtime.DesktopGitPlatform
import com.letta.mobile.desktop.runtime.DesktopGitStatus
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchPreference
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchSettings
import com.letta.mobile.desktop.runtime.desktopRuntimeHeapMb
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayAction
import com.letta.mobile.ui.host.OverlayPresentation
import com.letta.mobile.ui.host.OverlayPriority
import com.letta.mobile.ui.host.OverlayResponse
import com.letta.mobile.ui.host.OverlayTone
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

/** Progress of the Windows PortableGit download (letta-mobile-k5nbq.8). */
internal data class DesktopGitInstallState(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

/** What the local-runtime settings need from the outside world; faked in tests. */
internal interface DesktopLocalRuntimeBindings {
    val health: StateFlow<RuntimeHealth>
    val git: StateFlow<DesktopGitStatus>
    fun forceRestart()
    fun stop()
    fun start()

    /** Checks for git again; blocking. */
    fun recheckGit()

    /** Windows: download pinned PortableGit, then recheck. Throws on checksum mismatch. */
    fun installPortableGit()

    fun currentLaunchState(store: SecureSettingsStore): DesktopRuntimeLaunchState
}

internal object DefaultDesktopLocalRuntimeBindings : DesktopLocalRuntimeBindings {
    override val health: StateFlow<RuntimeHealth> get() = DesktopLocalRuntimeHost.appServer.health

    override val git: StateFlow<DesktopGitStatus> get() = DesktopLocalRuntimeHost.gitEnvironment.status

    override fun forceRestart() = DesktopLocalRuntimeHost.appServer.forceRestart()

    override fun stop() = DesktopLocalRuntimeHost.appServer.stopByUser()

    override fun start() = DesktopLocalRuntimeHost.appServer.startByUser()

    override fun recheckGit() {
        DesktopLocalRuntimeHost.gitEnvironment.refresh()
    }

    override fun installPortableGit() {
        DesktopLocalRuntimeHost.installPortableGit()
    }

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

    val git: StateFlow<DesktopGitStatus> get() = bindings.git

    private val _gitInstall = MutableStateFlow(DesktopGitInstallState())
    val gitInstall: StateFlow<DesktopGitInstallState> = _gitInstall.asStateFlow()

    fun forceRestart() = bindings.forceRestart()

    /** The card's on/off switch (G05): [on] false stops the runtime until it is turned on again. */
    fun setRunning(on: Boolean) = if (on) bindings.start() else bindings.stop()

    fun recheckGit() {
        scope.launch { withContext(io) { bindings.recheckGit() } }
    }

    fun installGit() {
        scope.launch {
            _gitInstall.value = DesktopGitInstallState(busy = true)
            val error = withContext(io) {
                runCatching { bindings.installPortableGit() }.exceptionOrNull()?.message
            }
            _gitInstall.value = DesktopGitInstallState(busy = false, message = error, isError = error != null)
        }
    }

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

/** The runtime is down, restarting, or switched off: the window says so and offers the fix. */
internal val RuntimeHealth.needsChatBanner: Boolean
    get() = restartPending || needsForceRestart || stoppedByUser

/**
 * letta-mobile-ckmub.3.15 (S13): the bundled runtime's notice in the window's overlay queue while it
 * is down (F03): "restarting" during backoff, Restart once automatic restarts gave up, Start when
 * the person stopped it. Null while the runtime is healthy.
 */
internal fun RuntimeHealth.runtimeBanner(): Overlay.Banner? {
    if (!needsChatBanner) return null
    val action = when {
        stoppedByUser -> OverlayAction("Start", DesktopLocalRuntimeHost.appServer::startByUser)
        needsForceRestart -> OverlayAction("Restart", DesktopLocalRuntimeHost.appServer::forceRestart)
        else -> null
    }
    return Overlay.Banner(
        message = "Local runtime: ",
        key = LOCAL_RUNTIME_BANNER_KEY,
        presentation = OverlayPresentation(OverlayPriority.High, durationMillis = null, tone = OverlayTone.Error),
        response = OverlayResponse(action),
    )
}

private const val LOCAL_RUNTIME_BANNER_KEY = "desktop-local-runtime"

/** The git line of the runtime card (G07). */
internal fun DesktopGitStatus.describe(): String = when (this) {
    DesktopGitStatus.Unknown -> "Checking…"
    is DesktopGitStatus.Available ->
        if (onInheritedPath) "Found in ${directory.path}." else "Found in ${directory.path} (added to the runtime's PATH)."
    is DesktopGitStatus.Missing -> when (platform) {
        DesktopGitPlatform.WINDOWS ->
            "Not found. Memory sync, diffs and branches need Git. Install Git here " +
                "(pinned Git for Windows MinGit); the runtime restarts once it is on PATH."
        DesktopGitPlatform.MAC ->
            "Not found. Memory sync, diffs and branches need Git. Run xcode-select --install in " +
                "Terminal (or install Git); it is picked up automatically once installed."
        DesktopGitPlatform.OTHER ->
            "Not found. Memory sync, diffs and branches need Git. Install it with your package manager."
    }
}

/** One-line description of the runtime's health for the card and the chat banner. */
internal fun RuntimeHealth.describe(): String = when {
    stoppedByUser -> "Stopped. Local agents are offline until you start the runtime again."
    gaveUp -> "Stopped after $consecutiveCrashes crashes in a minute. Automatic restart is off until you restart it."
    restartPending -> "Crashed (exit ${lastExitCode ?: "?"}). Restarting…"
    active -> if (consecutiveCrashes > 0) "Running (recovered from $consecutiveCrashes recent crash${if (consecutiveCrashes == 1) "" else "es"})." else "Running."
    stoppedUnexpectedly -> "Stopped unexpectedly (exit ${lastExitCode ?: "?"})."
    else -> "Not running. It starts when you connect to the local runtime."
}
