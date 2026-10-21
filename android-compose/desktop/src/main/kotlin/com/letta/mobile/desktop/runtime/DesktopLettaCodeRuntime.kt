package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.runtime.host.JvmLocalAppServerLauncher
import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerLaunch
import com.letta.mobile.data.transport.appserver.AppServerDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal data class DesktopLettaCodeInstallation(
    val nodeExecutable: File,
    val lettaEntryPoint: File,
)

internal data class DesktopRuntimeLocationInputs(
    val property: (String) -> String?,
    val environment: (String) -> String?,
    val resourcesRoot: File?,
    val isWindows: Boolean,
)

internal object DesktopLettaCodeRuntimeLocator {
    private const val NODE_PROPERTY = "letta.desktop.runtime.node"
    private const val LETTA_JS_PROPERTY = "letta.desktop.runtime.lettaJs"
    private const val NODE_ENV = "LETTA_DESKTOP_RUNTIME_NODE"
    private const val LETTA_JS_ENV = "LETTA_DESKTOP_RUNTIME_JS"

    fun locate(): DesktopLettaCodeInstallation? = locate(
        DesktopRuntimeLocationInputs(
            property = System::getProperty,
            environment = System::getenv,
            resourcesRoot = System.getProperty("compose.application.resources.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File),
            isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        ),
    )

    internal fun locate(inputs: DesktopRuntimeLocationInputs): DesktopLettaCodeInstallation? {
        explicitInstallation(inputs)?.let { return it.validatedOrNull() }
        if (!inputs.isWindows) return null
        val resourcesRoot = inputs.resourcesRoot ?: return null
        return sequenceOf(
            File(resourcesRoot, "letta-code-runtime"),
            File(resourcesRoot, "windows/letta-code-runtime"),
        ).map { runtimeRoot ->
            DesktopLettaCodeInstallation(
                nodeExecutable = File(runtimeRoot, "node.exe"),
                lettaEntryPoint = File(runtimeRoot, "node_modules/@letta-ai/letta-code/letta.js"),
            )
        }.mapNotNull { it.validatedOrNull() }.firstOrNull()
    }

    private fun explicitInstallation(inputs: DesktopRuntimeLocationInputs): DesktopLettaCodeInstallation? {
        val node = inputs.property(NODE_PROPERTY)?.takeIf(String::isNotBlank)
            ?: inputs.environment(NODE_ENV)?.takeIf(String::isNotBlank)
        val lettaJs = inputs.property(LETTA_JS_PROPERTY)?.takeIf(String::isNotBlank)
            ?: inputs.environment(LETTA_JS_ENV)?.takeIf(String::isNotBlank)
        return if (node != null && lettaJs != null) {
            DesktopLettaCodeInstallation(File(node), File(lettaJs))
        } else {
            null
        }
    }

    private fun DesktopLettaCodeInstallation.validatedOrNull(): DesktopLettaCodeInstallation? =
        takeIf { it.nodeExecutable.isFile && it.lettaEntryPoint.isFile }
}

/** The bundled node + letta.js, run with the user's backend directory and launch preferences (F05). */
internal fun desktopLocalAppServerLaunch(
    installation: DesktopLettaCodeInstallation,
    backendDirectory: File,
    context: DesktopRuntimeLaunchContext,
): LocalAppServerLaunch = LocalAppServerLaunch(
    command = listOf(installation.nodeExecutable.absolutePath, installation.lettaEntryPoint.absolutePath),
    backendDir = backendDirectory.absolutePath,
    environment = context.environment,
    workingDirectory = context.workingDirectory?.absolutePath,
)

/**
 * The desktop's binding of the shared [LocalAppServerHost] (letta-mobile-by4vm.110.4): the
 * bundled node + letta.js as the command, the user's backend directory and launch preferences,
 * a child-process launcher whose children are recorded for orphan reaping (G04), the git
 * environment watch (G07) and the runtime log file.
 */
internal object DesktopLocalRuntimeHost {
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
    private const val RESTART_HANDOFF_MS = 15_000L
    private val logLock = Any()

    /** Spawned children, so the next launch can reap a child this process left behind (G04). */
    private val spawnRegistry = DesktopListenerSpawnRegistry(
        file = File(System.getProperty("user.home"), ".letta-mobile/runtime/listener-spawn-registry.json"),
        processTable = JvmDesktopProcessTable,
        self = JvmDesktopProcessTable::self,
        logLine = { line -> appendLog(localRuntimeLogFile(), line) },
    )

    @Suppress("NoDetachedCoroutineLifecycle") // Lives as long as the desktop process, like this object.
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The bundled runtime: leases for the chat, health for the settings card and the chat banner. */
    val appServer = LocalAppServerHost(
        launcher = JvmLocalAppServerLauncher(onSpawned = ::recordSpawn),
        launch = ::nextLaunch,
        readyz = AppServerDiscovery(HttpClient(CIO))::readyz,
        scope = supervisorScope,
        logLine = { line -> appendLog(localRuntimeLogFile(), line) },
    )

    /**
     * Whether git is available to the runtime (G07). Watched from launch until found; an install
     * puts git on the next spawn's PATH and restarts a running child once.
     */
    val gitEnvironment = DesktopGitEnvironmentMonitor(
        probe = { DesktopGitProbe.forThisMachine().check() },
        runtimeActive = { appServer.health.value.active },
        restartRuntime = { appServer.forceRestart() },
    )

    /** letta-mobile-k5nbq.8: pinned MinGit into the per-user dir, then recheck so PATH and restart run. */
    fun installPortableGit() {
        gitEnvironment.installPortableGit(PortableGitInstaller(portableGitRoot()))
    }

    /**
     * Reconnects the chat to a restarted child without killing it. The chat's reconnect closes its
     * old lease before the new gateway acquires one; a short-lived bridge lease keeps the owner
     * count above zero across that gap so the fresh child is not stopped and spawned again.
     */
    suspend fun handOffAfterRestart(reconnect: () -> Unit) {
        val bridge = runCatching { appServer.acquire() }.getOrNull()
        try {
            reconnect()
        } finally {
            bridge?.let { lease ->
                supervisorScope.launch {
                    delay(RESTART_HANDOFF_MS.milliseconds)
                    lease.close()
                }
            }
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread(appServer::close, "letta-desktop-runtime-shutdown"))
        // Before the first spawn: a child a crashed predecessor left behind still holds its port.
        runCatching { spawnRegistry.reapOrphans() }
        supervisorScope.launch { gitEnvironment.watchUntilAvailable() }
    }

    /** `~/.letta-mobile/local-backend` unless overridden — see [DesktopLocalBackendDirectorySettings]. */
    fun defaultBackendDirectory(): File =
        File(System.getProperty("user.home"), ".letta-mobile/local-backend")

    /**
     * Directory the bundled local runtime uses for its data (agents,
     * conversations, `providers/auth.json`) — user-configurable from Settings
     * (see `DesktopLocalBackendDirectorySettingsCard`). Reads the live
     * override on every call, so a change takes effect the next time the
     * runtime process is (re)started, with no restart of the desktop app
     * itself required.
     */
    fun backendDirectory(): File =
        DesktopLocalBackendDirectoryPreference.override ?: defaultBackendDirectory()

    private fun nextLaunch(): LocalAppServerLaunch = desktopLocalAppServerLaunch(
        installation = DesktopLettaCodeRuntimeLocator.locate()
            ?: error("The bundled Letta Code runtime is missing from this desktop distribution"),
        backendDirectory = backendDirectory(),
        context = DesktopRuntimeLaunchPreference.currentContext(),
    )

    private fun recordSpawn(process: Process) {
        val pid = process.pid()
        spawnRegistry.onSpawned(pid)
        process.onExit().thenRun { spawnRegistry.onEnded(pid) }
    }

    private fun localRuntimeLogFile(): File =
        File(System.getProperty("user.home"), ".letta-mobile/logs/local-runtime.log")

    private fun appendLog(file: File, line: String) {
        synchronized(logLock) {
            file.parentFile.mkdirs()
            if (file.length() > MAX_LOG_BYTES) {
                val previous = File(file.parentFile, "${file.name}.previous")
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText("$line\n")
        }
    }
}
