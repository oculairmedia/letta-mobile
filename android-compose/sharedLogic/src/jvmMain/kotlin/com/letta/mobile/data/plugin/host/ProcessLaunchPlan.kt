package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.ResolvedTemplates
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Resource limits applied when launching a `process` plugin. */
data class ProcessCaps(
    val memoryMax: String = "256M",
    val cpuQuota: String = "50%",
    val tasksMax: Int = 64,
    val nodeHeapMegabytes: Int = 256,
)

/** How the plugin process is confined. */
sealed interface ProcessConfinement {
    /**
     * In a transient `systemd-run --user --scope` with [ProcessCaps] as unit properties. [busEnv] is
     * what systemd-run needs to reach the user manager; `env -u` strips it again before the plugin runs.
     */
    data class SystemdScope(val systemdRun: String, val envBinary: String, val busEnv: Map<String, String>) : ProcessConfinement

    /** No cgroup caps here; a node plugin still gets its heap capped by flag. */
    data object Unconfined : ProcessConfinement
}

/** Decides the [ProcessConfinement]; the default probes once whether systemd-run works on this host. */
fun interface ProcessConfinementSource {
    fun confinement(): ProcessConfinement
}

/**
 * Probes `systemd-run --user --scope` once (lazily, on first use) and caches the answer: present
 * on the PATH and able to start a capped scope with only the bus variables in its environment.
 */
internal class SystemdScopeProbe(
    private val hostEnv: Map<String, String>,
    private val caps: ProcessCaps = ProcessCaps(),
) : ProcessConfinementSource {
    private val cached: ProcessConfinement by lazy(::probe)

    override fun confinement(): ProcessConfinement = cached

    private fun probe(): ProcessConfinement {
        val path = hostEnv["PATH"].orEmpty()
        val systemdRun = ProcessLaunchPlanner.which("systemd-run", path) ?: return ProcessConfinement.Unconfined
        val envBinary = ProcessLaunchPlanner.which("env", path) ?: return ProcessConfinement.Unconfined
        val trueBinary = ProcessLaunchPlanner.which("true", path) ?: return ProcessConfinement.Unconfined
        val scope = ProcessConfinement.SystemdScope(systemdRun, envBinary, hostEnv.filterKeys { it in BUS_KEYS })
        val command = ProcessLaunchPlanner.scopePrefix(scope, caps) + trueBinary
        return if (succeeds(command, scope.busEnv + ("PATH" to path))) scope else ProcessConfinement.Unconfined
    }

    private fun succeeds(command: List<String>, env: Map<String, String>): Boolean = try {
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply { clear(); putAll(env) }
        val process = builder.start()
        val exited = process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        exited && process.exitValue() == 0
    } catch (_: IOException) {
        false
    }

    companion object {
        /** What systemd-run --user needs to reach the user's service manager. */
        val BUS_KEYS: Set<String> = setOf("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")

        private const val PROBE_SECONDS = 5L

        /** The host's one probe, shared by every driver. */
        val DEFAULT: SystemdScopeProbe by lazy { SystemdScopeProbe(System.getenv()) }
    }
}

/** Where a plugin is spawned: the `PATH` it gets (and bare commands resolve on), its confinement and caps. */
data class ProcessHostEnvironment(
    val path: String,
    val confinement: ProcessConfinement,
    val caps: ProcessCaps = ProcessCaps(),
)

/** A refused launch: the manifest names something that cannot run from its package. */
class PluginLaunchException(message: String) : Exception(message)

/** Everything a spawn needs. [toString] lists env keys only: values may be secrets. */
class ProcessLaunchPlan(val command: List<String>, val environment: Map<String, String>, val directory: File) {
    fun start(): Process {
        val builder = ProcessBuilder(command).directory(directory)
        builder.environment().apply { clear(); putAll(environment) }
        return builder.start()
    }

    override fun toString(): String = "ProcessLaunchPlan($command, env=${environment.keys}, cwd=$directory)"
}

/**
 * Builds the spawn of a manifest's `runtime.process`: the command resolved inside the package (or a
 * bare program on [path]), the working directory inside the package, and an environment of `PATH`
 * plus the manifest's resolved `env` substitutions and nothing else from the host.
 */
object ProcessLaunchPlanner {
    private val NODE_COMMANDS = setOf("node", "nodejs")

    fun plan(
        runtime: PluginRuntime.Process,
        packageDir: Path,
        env: ResolvedTemplates,
        host: ProcessHostEnvironment,
    ): ProcessLaunchPlan {
        val (path, confinement, caps) = host
        val root = packageDir.toAbsolutePath().normalize()
        val program = resolveCommand(runtime.command, root, path)
        val heapFlag = listOf("--max-old-space-size=${caps.nodeHeapMegabytes}")
            .takeIf { File(program).name in NODE_COMMANDS && confinement is ProcessConfinement.Unconfined }
            .orEmpty()
        val pluginCommand = listOf(program) + heapFlag + runtime.args
        val pluginEnv = mapOf("PATH" to path) + env.reveal()
        val directory = inside(root, runtime.cwd, "runtime.cwd").toFile()
        return when (confinement) {
            ProcessConfinement.Unconfined -> ProcessLaunchPlan(pluginCommand, pluginEnv, directory)
            is ProcessConfinement.SystemdScope -> {
                val busKeys = confinement.busEnv.keys - pluginEnv.keys
                val strip = busKeys.flatMap { listOf("-u", it) }
                val command = scopePrefix(confinement, caps) + confinement.envBinary + strip + pluginCommand
                ProcessLaunchPlan(command, pluginEnv + confinement.busEnv.filterKeys { it in busKeys }, directory)
            }
        }
    }

    /** `systemd-run --user --scope` with the caps, up to and including `--`. */
    fun scopePrefix(scope: ProcessConfinement.SystemdScope, caps: ProcessCaps): List<String> = listOf(
        scope.systemdRun, "--user", "--scope", "--quiet",
        "-p", "MemoryMax=${caps.memoryMax}",
        "-p", "CPUQuota=${caps.cpuQuota}",
        "-p", "TasksMax=${caps.tasksMax}",
        "--",
    )

    /** The first executable [name] on [path], or null. */
    fun which(name: String, path: String): String? = path.split(File.pathSeparatorChar)
        .filter(String::isNotEmpty)
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

    private fun resolveCommand(command: String, root: Path, path: String): String =
        if (command.any { it == '/' || it == '\\' }) {
            inside(root, command, "runtime.command").toString()
        } else {
            which(command, path) ?: throw PluginLaunchException("'$command' is not on the plugin's PATH")
        }

    private fun inside(root: Path, relative: String, what: String): Path {
        val resolved = root.resolve(relative.removePrefix("./")).normalize()
        if (!resolved.startsWith(root)) throw PluginLaunchException("$what '$relative' leaves the package")
        return resolved
    }
}
