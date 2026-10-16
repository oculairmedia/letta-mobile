package com.letta.mobile.data.runtime.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A launch ready to hand the launcher: the server command line, its token file and the token. */
internal class PreparedLocalAppServerLaunch(
    val launch: LocalAppServerLaunch,
    val tokenFile: String,
    val token: String,
)

/** The platform's [spec] with the `server` arguments and the local-backend environment added. */
internal fun preparedLocalAppServerLaunch(spec: LocalAppServerLaunch, token: String): PreparedLocalAppServerLaunch {
    val tokenFile = "${spec.backendDir}/${LocalAppServerHost.TOKEN_FILE_NAME}"
    val launch = spec.copy(
        command = spec.command + listOf(
            "server", "--backend", "local",
            "--listen", "ws://127.0.0.1:0",
            "--ws-auth", "capability-token", "--ws-token-file", tokenFile,
        ),
        environment = spec.environment + mapOf(
            "LETTA_LOCAL_BACKEND_EXPERIMENTAL" to "1",
            "LETTA_LOCAL_BACKEND_DIR" to spec.backendDir,
        ),
    )
    return PreparedLocalAppServerLaunch(launch, tokenFile, token)
}

/** How waiting for a started server ended; a wait that timed out has no outcome. */
internal sealed interface LocalAppServerReadyOutcome {
    /** It announced [url] and `/readyz` answers. */
    data class Ready(val url: String) : LocalAppServerReadyOutcome

    /** It ended before it was ready. */
    data class Ended(val exitCode: Int?) : LocalAppServerReadyOutcome
}

/**
 * Pumps [process]'s output to [onStdout]/[onStderr] and completes with the announced listen URL,
 * or null when stdout closes first (the server ended).
 */
internal fun CoroutineScope.announcedListenUrl(
    process: LocalAppServerProcess,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): CompletableDeferred<String?> {
    val announced = CompletableDeferred<String?>()
    launch { process.stderr.collect(onStderr) }
    launch {
        process.stdout.collect { line ->
            onStdout(line)
            if (!announced.isCompleted) parseLocalAppServerListenUrl(line)?.let(announced::complete)
        }
        announced.complete(null)
    }
    return announced
}

/** The loopback URL from letta-code's `Listening on ws://127.0.0.1:<port>` line, else null. */
fun parseLocalAppServerListenUrl(line: String): String? =
    Regex("""Listening on (ws://(?:127\.0\.0\.1|localhost):\d+)\b""")
        .find(line)
        ?.groupValues
        ?.get(1)
