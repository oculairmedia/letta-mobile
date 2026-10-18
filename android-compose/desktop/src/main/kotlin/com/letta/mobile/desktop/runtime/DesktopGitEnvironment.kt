package com.letta.mobile.desktop.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlin.time.Duration.Companion.milliseconds

/** Whether the bundled runtime can find git (letta-mobile-zxk2n.1, G07). */
internal sealed interface DesktopGitStatus {
    /** Not checked yet. */
    data object Unknown : DesktopGitStatus

    /**
     * Git is installed. [directory] holds the executable; [onInheritedPath] is false when it was
     * installed after this app started (or lives in a standard location off PATH), so the next
     * spawn has to add [directory] to the child's PATH.
     */
    data class Available(val directory: File, val onInheritedPath: Boolean) : DesktopGitStatus

    /** No git: memory sync, diffs and branch tools in the runtime will fail until it is installed. */
    data class Missing(val platform: DesktopGitPlatform) : DesktopGitStatus
}

internal enum class DesktopGitPlatform(val installUrl: String) {
    WINDOWS("https://git-scm.com/download/win"),
    MAC("https://git-scm.com/download/mac"),
    OTHER("https://git-scm.com/downloads"),
}

/** What the probe reads from the machine; faked in tests. */
internal data class DesktopGitProbeInputs(
    val platform: DesktopGitPlatform,
    /** PATH as this process inherited it at launch. */
    val inheritedPath: String?,
    /**
     * PATH as the OS has it now (on Windows the user and machine values from the registry), which
     * differs from [inheritedPath] after an install. Null when it cannot be read.
     */
    val currentPath: () -> String?,
    /** Standard install directories checked even when they are on no PATH. */
    val wellKnownDirectories: List<File>,
    val isExecutable: (File) -> Boolean,
    /** macOS: the git binaries of the Command Line Tools or Xcode that make `/usr/bin/git` real. */
    val macDeveloperToolsGit: List<File> = emptyList(),
)

/**
 * Finds git the way the runtime child will: on PATH first, then in the standard install
 * directories. On macOS `/usr/bin/git` is only a stub that asks to install the Command Line Tools,
 * so it counts only when the tools are installed (a well-known directory below).
 */
internal class DesktopGitProbe(private val inputs: DesktopGitProbeInputs) {
    fun check(): DesktopGitStatus {
        val executableName = if (inputs.platform == DesktopGitPlatform.WINDOWS) "git.exe" else "git"
        val separator = if (inputs.platform == DesktopGitPlatform.WINDOWS) ';' else ':'
        val inherited = pathDirectories(inputs.inheritedPath, separator)
        inherited.firstOrNull { inputs.isExecutable(File(it, executableName)) && isTrusted(it) }?.let {
            return DesktopGitStatus.Available(it, onInheritedPath = true)
        }
        val candidates = pathDirectories(inputs.currentPath(), separator) + inputs.wellKnownDirectories
        return candidates.firstOrNull { inputs.isExecutable(File(it, executableName)) && isTrusted(it) }
            ?.let { DesktopGitStatus.Available(it, onInheritedPath = false) }
            ?: DesktopGitStatus.Missing(inputs.platform)
    }

    /** The macOS `/usr/bin/git` stub works only once the Command Line Tools are installed. */
    private fun isTrusted(directory: File): Boolean =
        inputs.platform != DesktopGitPlatform.MAC ||
            directory.invariantSeparatorsPath != "/usr/bin" ||
            inputs.macDeveloperToolsGit.any(inputs.isExecutable)

    private fun pathDirectories(path: String?, separator: Char): List<File> =
        path.orEmpty().split(separator).map { it.trim().trim('"') }.filter { it.isNotEmpty() }.map(::File)

    companion object {
        fun forThisMachine(): DesktopGitProbe {
            val os = System.getProperty("os.name").orEmpty()
            val platform = when {
                os.startsWith("Windows", ignoreCase = true) -> DesktopGitPlatform.WINDOWS
                os.startsWith("Mac", ignoreCase = true) -> DesktopGitPlatform.MAC
                else -> DesktopGitPlatform.OTHER
            }
            return DesktopGitProbe(
                DesktopGitProbeInputs(
                    platform = platform,
                    inheritedPath = System.getenv("PATH"),
                    currentPath = { if (platform == DesktopGitPlatform.WINDOWS) WindowsRegistryPath.read() else null },
                    wellKnownDirectories = wellKnownDirectories(platform),
                    isExecutable = { it.isFile && it.canExecute() },
                    macDeveloperToolsGit = listOf(
                        File("/Library/Developer/CommandLineTools/usr/bin/git"),
                        File("/Applications/Xcode.app/Contents/Developer/usr/bin/git"),
                    ),
                ),
            )
        }

        private fun wellKnownDirectories(platform: DesktopGitPlatform): List<File> = when (platform) {
            DesktopGitPlatform.WINDOWS -> listOfNotNull(
                System.getenv("ProgramFiles")?.let { File(it, "Git/cmd") },
                System.getenv("LOCALAPPDATA")?.let { File(it, "Programs/Git/cmd") },
                portableGitCmdDirectory(),
            )
            DesktopGitPlatform.MAC -> listOf(
                File("/Library/Developer/CommandLineTools/usr/bin"),
                File("/opt/homebrew/bin"),
                File("/usr/local/bin"),
            )
            DesktopGitPlatform.OTHER -> listOf(File("/usr/bin"), File("/usr/local/bin"))
        }
    }
}

/**
 * Keeps [status] current and reacts to an install: when git turns up off the inherited PATH after
 * not being available, its directory goes onto the next spawn's PATH ([applyPathDirectory]) and a
 * running runtime restarts once ([restartRuntime]) so the child can see it. Later checks that
 * still find git do not restart again.
 */
internal class DesktopGitEnvironmentMonitor(
    private val probe: () -> DesktopGitStatus,
    private val runtimeActive: () -> Boolean,
    private val restartRuntime: () -> Unit,
    private val applyPathDirectory: (File?) -> Unit,
) {
    private val _status = MutableStateFlow<DesktopGitStatus>(DesktopGitStatus.Unknown)
    val status: StateFlow<DesktopGitStatus> = _status.asStateFlow()

    /** Checks again now; blocking (it may read the registry). */
    fun refresh(): DesktopGitStatus {
        val next = probe()
        val previous = _status.getAndUpdate { next }
        val offPath = (next as? DesktopGitStatus.Available)?.takeUnless { it.onInheritedPath }?.directory
        applyPathDirectory(offPath)
        if (needsRestart(previous, offPath)) restartRuntime()
        return next
    }

    /** Git just became usable from a directory the running child cannot see yet. */
    private fun needsRestart(previous: DesktopGitStatus, offPath: File?): Boolean =
        offPath != null && previous !is DesktopGitStatus.Available && runtimeActive()

    /** Re-checks every [intervalMs] while git is not available, so an install is noticed without a click. */
    suspend fun watchUntilAvailable(intervalMs: Long = DEFAULT_WATCH_INTERVAL_MS) {
        while (refresh() !is DesktopGitStatus.Available) delay(intervalMs.milliseconds)
    }

    companion object {
        const val DEFAULT_WATCH_INTERVAL_MS = 30_000L
    }
}

/** Reads the user and machine PATH from the Windows registry, which a fresh install updates. */
internal object WindowsRegistryPath {
    private const val TIMEOUT_SECONDS = 5L
    private val KEYS = listOf(
        "HKCU\\Environment",
        "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment",
    )

    fun read(): String? = KEYS.mapNotNull(::query).takeIf { it.isNotEmpty() }?.joinToString(";")

    private fun query(key: String): String? = runCatching {
        val process = ProcessBuilder("reg", "query", key, "/v", "Path").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        parseRegQueryPath(output)?.let(::expandWindowsVariables)
    }.getOrNull()
}

/** The value of the `Path` line of `reg query <key> /v Path` output. */
internal fun parseRegQueryPath(output: String): String? =
    output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("Path", ignoreCase = true) && "REG_" in it }
        ?.substringAfter("REG_")
        ?.substringAfter(' ')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** Expands `%NAME%` references (REG_EXPAND_SZ) from [environment]; unknown names stay as written. */
internal fun expandWindowsVariables(value: String, environment: (String) -> String? = System::getenv): String =
    Regex("%([^%]+)%").replace(value) { match -> environment(match.groupValues[1]) ?: match.value }
