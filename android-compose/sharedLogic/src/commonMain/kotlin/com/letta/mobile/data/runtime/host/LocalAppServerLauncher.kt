package com.letta.mobile.data.runtime.host

import kotlinx.coroutines.flow.Flow

/**
 * One App Server launch. The platform gives [LocalAppServerHost] the
 * letta entry point as [command] and where its data lives; the host hands the launcher a copy with
 * the `server` arguments, the loopback listener, the capability-token flags and the local-backend
 * environment added. The platform never builds those.
 *
 * @param command the argv: from the platform, the prefix that runs letta-code (`[node, /path/letta.js]`
 *   or `[letta]`); to the launcher, the whole `server` command line.
 * @param backendDir absolute path of the local backend root (`LETTA_LOCAL_BACKEND_DIR`); the token
 *   file lives inside it.
 * @param environment extra environment for the server, on top of what the platform inherits.
 * @param workingDirectory the server's working directory; null inherits the host process's.
 */
data class LocalAppServerLaunch(
    val command: List<String>,
    val backendDir: String,
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
)

/**
 * Starts one App Server for [LocalAppServerHost]. Bind-only: the platform runs the command the way
 * it runs code (a child process on the JVM, libnode in-process on Android) and reports its output
 * and end. Every policy (arguments, readiness, restarts, leases) is the host's.
 */
fun interface LocalAppServerLauncher {
    /**
     * True when the server can start only once per OS process (Android's in-process libnode):
     * the host then never stops it when idle and never restarts it.
     */
    val singleLaunch: Boolean get() = false

    /**
     * Writes [token] to [tokenFile], readable by this user only and creating its directory, then
     * starts the [launch]'s command with its environment added, in its working directory. Throws
     * [IllegalStateException] when it cannot start.
     */
    fun start(launch: LocalAppServerLaunch, tokenFile: String, token: String): LocalAppServerProcess
}

/** A started App Server, as [LocalAppServerLauncher] reports it. */
interface LocalAppServerProcess {
    /** Lines the server writes to stdout, in order; completes when stdout closes. Collected once. */
    val stdout: Flow<String>

    /** Lines the server writes to stderr; completes when stderr closes. Collected once. */
    val stderr: Flow<String>

    val isAlive: Boolean

    /** Suspends until the server has ended; its exit code when the platform knows it. */
    suspend fun awaitExit(): Int?

    /** Stops the server and anything it started, waiting a bounded time for it to go. Idempotent. */
    fun kill()
}
