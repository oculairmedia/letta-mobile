package com.letta.mobile.desktop.runtime

import java.io.File
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

/** Owns the bundled local Letta Code child for the lifetime of the desktop process. */
internal interface DesktopLocalRuntimeLease : AutoCloseable {
    val serverUrl: String
}

internal interface DesktopLocalRuntimeLifecycle : AutoCloseable {
    fun acquire(): DesktopLocalRuntimeLease
}

internal object DesktopLocalRuntimeHost : DesktopLocalRuntimeLifecycle {
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
    private const val RESTART_HANDOFF_MS = 15_000L
    private val logLock = Any()
    private val manager = DesktopLocalRuntimeManager(
        installationProvider = DesktopLettaCodeRuntimeLocator::locate,
        backendDirectory = ::backendDirectory,
        processLauncher = DesktopRuntimeProcessLauncher { spec ->
            val process = ProcessBuilder(spec.command).apply {
                environment().putAll(spec.environment)
                spec.workingDirectory?.let(::directory)
            }.start()
            JvmDesktopRuntimeProcess(process)
        },
        logLine = { line -> appendLog(localRuntimeLogFile(), line) },
        launchContext = DesktopRuntimeLaunchPreference::currentContext,
    )

    // Concurrent so the supervisor can read it from process threads without this object's monitor.
    @Suppress("NoProcessGlobalMutableState") // This object is the one per-process runtime host; the set is its own state.
    private val owners: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Suppress("NoDetachedCoroutineLifecycle") // Lives as long as the desktop process, like this object.
    private val supervisorScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** Crash supervision for the child (letta-mobile-bzvro.3, F03). */
    private val supervisor = DesktopRuntimeSupervisor(
        manager = manager,
        scope = supervisorScope,
        wanted = { owners.isNotEmpty() },
        logLine = { line -> appendLog(localRuntimeLogFile(), line) },
    )

    /** Health of the bundled runtime, polled by the settings card and the chat banner. */
    val health: kotlinx.coroutines.flow.StateFlow<com.letta.mobile.data.runtime.supervisor.RuntimeHealth>
        get() = supervisor.health

    /** Emits each time the supervisor restarted the child on its own; the chat must reconnect. */
    val restarted: kotlinx.coroutines.flow.SharedFlow<String>
        get() = supervisor.restarted

    fun forceRestart() = supervisor.forceRestart()

    fun onSystemSuspended() = supervisor.onSuspended()

    fun onSystemResumed() = supervisor.onResumed()

    /**
     * Reconnects the chat to a restarted child without killing it. The chat's reconnect closes its
     * old lease before the new gateway acquires one; a short-lived bridge lease keeps the owner
     * count above zero across that gap so the fresh child is not stopped and spawned again.
     */
    suspend fun handOffAfterRestart(reconnect: () -> Unit) {
        val bridge = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { acquire() }.getOrNull() }
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
        Runtime.getRuntime().addShutdownHook(Thread(::close, "letta-desktop-runtime-shutdown"))
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

    @Synchronized
    override fun acquire(): DesktopLocalRuntimeLease {
        val owner = java.util.UUID.randomUUID().toString()
        val url = supervisor.ensureStarted()
        owners += owner
        return object : DesktopLocalRuntimeLease {
            override val serverUrl: String = url
            private var released = false

            override fun close() {
                synchronized(this@DesktopLocalRuntimeHost) {
                    if (released) return
                    released = true
                    owners -= owner
                    if (owners.isEmpty()) manager.close()
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        owners.clear()
        manager.close()
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

private class JvmDesktopRuntimeProcess(
    private val process: Process,
) : DesktopRuntimeProcess {
    override val stdout = process.inputStream.bufferedReader()
    override val stderr = process.errorStream.bufferedReader()
    override val isAlive: Boolean get() = process.isAlive
    override val descendants: List<DesktopRuntimeProcessHandle>
        get() = process.descendants().map(::JvmDesktopRuntimeProcessHandle).toList()
    override val exitCodeOrNull: Int? get() = runCatching { process.exitValue() }.getOrNull()
    override fun destroy() = process.destroy()
    override fun destroyForcibly() = process.destroyForcibly().let { Unit }
    override fun waitFor(timeoutMs: Long): Boolean =
        process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun onExit(callback: (exitCode: Int?) -> Unit) {
        process.onExit().thenAccept { ended -> callback(runCatching { ended.exitValue() }.getOrNull()) }
    }
}

private class JvmDesktopRuntimeProcessHandle(
    private val handle: ProcessHandle,
) : DesktopRuntimeProcessHandle {
    override val isAlive: Boolean get() = handle.isAlive
    override fun destroy() { handle.destroy() }
    override fun destroyForcibly() { handle.destroyForcibly() }
}
