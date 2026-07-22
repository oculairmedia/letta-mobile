package com.letta.mobile.appservercli

/**
 * Result of probing the Node/Letta executables and their App Server capabilities
 * (letta-mobile-lgns8.6).
 *
 * Makes the exact runtime observable ("exact Node/Letta versions and
 * capabilities are observable") so a supervisor can pin/verify what it launches
 * rather than trusting PATH resolution blindly.
 */
data class ExecutableProbeResult(
    val lettaCommand: String,
    val lettaVersion: String?,
    val nodeVersion: String?,
    /** True if the executable understands the newer `server --backend local` syntax. */
    val supportsServerBackendLocal: Boolean,
    /** True if the executable understands the `app-server` subcommand. */
    val supportsAppServer: Boolean,
) {
    /**
     * The App Server invocation verb to use, preferring the modern
     * `server --backend local --listen` form when available and falling back to
     * the legacy `app-server --listen`.
     *
     * @throws IllegalStateException if neither syntax is supported.
     */
    fun appServerInvocation(): List<String> = when {
        supportsServerBackendLocal -> listOf("server", "--backend", "local", "--listen")
        supportsAppServer -> listOf("app-server", "--listen")
        else -> error(
            "letta executable '$lettaCommand' supports neither 'server --backend local' " +
                "nor 'app-server'; cannot start App Server",
        )
    }
}

/**
 * Runs a short-lived command and returns its captured stdout (trimmed) plus exit
 * code, or null on failure. Injected so [ExecutableProbe] is unit-testable
 * without spawning real processes.
 */
fun interface CommandRunner {
    /** @return Pair(exitCode, combinedOutput) or null if the process could not start. */
    fun run(command: List<String>, timeoutMs: Long): CommandResult?
}

data class CommandResult(val exitCode: Int, val output: String)

/**
 * Probes a Letta executable for its version, the underlying Node version, and
 * which App Server invocation syntax it supports.
 */
class ExecutableProbe(
    private val runner: CommandRunner,
    private val probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
) {
    fun probe(lettaCommand: String): ExecutableProbeResult {
        val lettaVersion = runner.run(listOf(lettaCommand, "--version"), probeTimeoutMs)
            ?.takeIf { it.exitCode == 0 }
            ?.output
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val nodeVersion = runner.run(listOf("node", "--version"), probeTimeoutMs)
            ?.takeIf { it.exitCode == 0 }
            ?.output
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        // Capability-detect via help output. A supported subcommand appears in
        // the top-level help listing.
        val help = runner.run(listOf(lettaCommand, "--help"), probeTimeoutMs)?.output.orEmpty()
        val serverHelp = runner.run(listOf(lettaCommand, "server", "--help"), probeTimeoutMs)

        val supportsAppServer = help.contains("app-server")
        val supportsServerBackendLocal = serverHelp != null &&
            serverHelp.exitCode == 0 &&
            serverHelp.output.contains("--backend")

        return ExecutableProbeResult(
            lettaCommand = lettaCommand,
            lettaVersion = lettaVersion,
            nodeVersion = nodeVersion,
            supportsServerBackendLocal = supportsServerBackendLocal,
            supportsAppServer = supportsAppServer,
        )
    }

    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MS: Long = 10_000
    }
}
