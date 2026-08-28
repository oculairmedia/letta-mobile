package com.letta.mobile.data.controller.extras

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): the JVM/Android [IrohCliRunner]
 * that actually spawns `meridian agent-message send` as a subprocess.
 *
 * The CLI binary is configurable: the production wrapper distribution
 * `:iroh-wrapper-cli` produces an installable `meridian-iroh-wrapper` binary
 * with the `agent-message send` subcommand wired in (or operators can use the
 * Android `:cli` module's `:cli:run -PcliArgs="agent-message send ..."` headless
 * path in CI/dev). For the production wrapper process, the binary path is
 * passed in by the controller (see `AppServerServeIrohCommand`).
 *
 * Body is supplied via `--body-file -` and piped through stdin. This is the
 * load-bearing choice that avoids the shell-quoting regression pinned by
 * `multiLineBodyRoundTripsViaStdin`: stdin is byte-exact, no quoting layer
 * collapses newlines or strips embedded quotes. Using `--body "<...>"` here
 * would re-create the original Meridian→Lester bug.
 *
 * Threading: `send` is `suspend` and switches to [Dispatchers.IO] for the
 * blocking `Process.waitFor` + stdio drain. Cancellation is honored — the
 * child process is destroyed on coroutine cancellation so a hung CLI cannot
 * outlive its invocation deadline.
 *
 * The CLI exit-code contract (mirrors `AgentMessageSendCommand`):
 *   - 0 → delivered
 *   - 1 → unaddressable or failed (distinguished by JSON stdout)
 * The stdout JSON shape is `{"ok":true,"delivered":true,"msgId":"..."}` for
 * delivered and `{"ok":false,"delivered":false,...}` for failures — see
 * `agentSendResultJson` in `:cli/.../AgentMessageSendCommand.kt`. We parse
 * with a hand-rolled scanner because adding a JSON dependency to
 * `sharedLogic` just for this would be the wrong move; the contract is small
 * and stable.
 */
class DefaultIrohCliRunner(
    /**
     * Maximum wall-clock time for the CLI to finish (spawn + write stdin +
     * drain stdout + exit). The dispatcher's
     * `ExternalToolDispatcher.INVOCATION_TIMEOUT_MS` is 120s; we set the CLI
     * deadline slightly shorter (default 90s) so the dispatcher timeout can
     * synthesize a matched is_error response WITHOUT racing the child
     * destroy. Tests override this.
     */
    private val invocationTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * Optional hook for tests that need to observe the process before/after.
     * Production code leaves it null.
     */
    private val processObserver: ((Process, String) -> Unit)? = null,
) : IrohCliRunner {

    override suspend fun send(
        binary: String,
        fromAgentId: String,
        toAgentId: String,
        body: String,
        paths: IrohCliPaths,
    ): IrohCliSendResult {
        // Generate the idempotency id at the wrapper so the CLI's default
        // `msg-${UUID}` only fires when an upstream caller didn't pin one.
        // For this tool surface the agent never supplies a msgId, so we mint
        // one here and surface it back on Delivered for the agent's log.
        val msgId = "msg-${UUID.randomUUID()}"

        val command = buildCommand(
            binary = binary,
            fromAgentId = fromAgentId,
            toAgentId = toAgentId,
            msgId = msgId,
            paths = paths,
        )

        return try {
            runCli(command, body, msgId, toAgentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IrohCliSendResult.Failed(
                toAgentId = toAgentId,
                reason = "cli_invocation_failed: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    /**
     * Spawn the CLI, write the body to stdin, parse the JSON stdout, and
     * collapse any non-zero exit into the typed result. Never throws.
     */
    private suspend fun runCli(
        command: List<String>,
        body: String,
        msgId: String,
        toAgentId: String,
    ): IrohCliSendResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        processObserver?.invoke(process, "spawned")

        // Write body to stdin, then close stdin so the CLI sees EOF. UTF-8
        // explicitly — the CLI's --body-file contract is UTF-8 and the body
        // may contain non-ASCII characters. Done BEFORE we wait on the
        // process so a stuck stdin write doesn't block the timeout path.
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        } catch (e: Exception) {
            process.destroyForcibly()
            return@withContext IrohCliSendResult.Failed(
                toAgentId = toAgentId,
                reason = "stdin_write_failed: ${e.message ?: e::class.simpleName}",
            )
        }

        // Wait for the process first (with the invocation deadline). Only
        // AFTER the process has exited (or been destroyed) do we drain
        // stdout/stderr — reading those streams before the child exits can
        // deadlock on a child that writes a lot and a small pipe buffer.
        //
        // Implementation note: we deliberately AVOID withTimeoutOrNull here.
        // The wrapper is reached from production dispatcher (Dispatchers.IO,
        // real time) AND from tests under kotlinx-coroutines-test (virtual
        // time scheduler). withTimeoutOrNull observes the calling context's
        // time source, which under a virtual scheduler means the timeout
        // never fires against a blocking real waitFor(). Polling
        // `isAlive` on the IO dispatcher uses real wall time and is
        // scheduler-agnostic.
        val deadlineMs = invocationTimeoutMs
        val exitCode = withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            while (process.isAlive) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                if (elapsedMs >= deadlineMs) {
                    return@withContext null
                }
                // 50ms poll granularity — coarse enough to avoid pegging
                // a CPU, fine enough that destroyForcibly lands within one
                // poll of the deadline.
                delay(50)
            }
            process.exitValue()
        }
        if (exitCode == null) {
            // Timed out — destroy the child and surface a Failed so the
            // dispatcher's is_error answer guarantee holds. The drain below
            // will pick up whatever the child had buffered before SIGKILL.
            process.destroyForcibly()
            // Best-effort: drain whatever the child wrote before the kill so
            // a future debugging session has the stderr to look at. We don't
            // gate the return on this; the dispatcher's 5-minute window has
            // its own watchdog.
            val stderrTail = runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            }.getOrNull()?.take(MAX_REASON_LEN)
            val reason = "cli_timeout_after_${invocationTimeoutMs}ms" +
                (stderrTail?.let { ": ${it.take(200)}" } ?: "")
            return@withContext IrohCliSendResult.Failed(
                toAgentId = toAgentId,
                reason = reason,
            )
        }

        // Process has exited — safe to drain (and it WILL be empty/small).
        val stdout = runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        }.getOrElse { "" }
        val stderr = runCatching {
            process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        }.getOrElse { "" }

        if (exitCode == 0) {
            val parsed = parseCliResultJson(stdout, msgId = msgId, toAgentId = toAgentId)
            processObserver?.invoke(process, "exit_0")
            return@withContext parsed
        } else {
            processObserver?.invoke(process, "exit_nonzero")
            return@withContext parseFailureFromStderr(stderr, stdout, toAgentId)
        }
    }

    /**
     * Build the CLI argv. List<String>, never `bash -c "..."` — every token
     * is a discrete element so the OS exec layer never sees a shell. This
     * is the second half of the load-bearing no-shell choice: combined with
     * `--body-file -` it makes multi-line bodies round-trip exactly.
     */
    private fun buildCommand(
        binary: String,
        fromAgentId: String,
        toAgentId: String,
        msgId: String,
        paths: IrohCliPaths,
    ): List<String> = buildList {
        add(binary)
        add("agent-message")
        add("send")
        add("--from"); add(fromAgentId)
        add("--to"); add(toAgentId)
        add("--msg-id"); add(msgId)
        add("--body-file"); add("-")
        if (paths.identityDir != null) {
            add("--identity-dir"); add(paths.identityDir)
        }
        if (paths.addressStore != null) {
            add("--address-store"); add(paths.addressStore)
        }
    }

    /**
     * Hand-rolled JSON parse for the CLI's result contract — the contract is
     * three lines in `agentSendResultJson` and stable, so a full kotlinx-
     * serialization dependency here would be overkill. Returns Delivered on
     * `{"ok":true,...}` and Failed on anything else. Unaddressable is
     * surfaced by [parseFailureFromStderr] (the CLI exits 1 on both
     * unaddressable AND failed — the JSON `error` field distinguishes).
     */
    private fun parseCliResultJson(
        stdout: String,
        msgId: String,
        toAgentId: String,
    ): IrohCliSendResult {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) {
            return IrohCliSendResult.Failed(
                toAgentId = toAgentId,
                reason = "empty_stdout_exit_0",
            )
        }
        return runCatching {
            val payload = Json.parseToJsonElement(trimmed).jsonObject
            val parsed = CliSendResultPayload.decode(payload, fallbackToAgentId = toAgentId)
            when {
                parsed.accepted && parsed.applicationDelivered ->
                    IrohCliSendResult.Delivered(msgId = parsed.msgId)
                parsed.accepted && !parsed.applicationDelivered ->
                    IrohCliSendResult.Accepted(msgId = parsed.msgId, toAgentId = toAgentId)
                else -> if (parsed.error == "unaddressable") {
                    IrohCliSendResult.Unaddressable(
                        toAgentId = parsed.toAgentId,
                        reason = parsed.reason ?: "unaddressable",
                    )
                } else {
                    IrohCliSendResult.Failed(
                        toAgentId = parsed.toAgentId,
                        reason = parsed.reason ?: parsed.error ?: "ok_false_exit_0",
                    )
                }
            }
        }.getOrElse { error ->
            IrohCliSendResult.Failed(
                toAgentId = toAgentId,
                reason = "invalid_cli_result: ${error.message ?: error::class.simpleName}",
            )
        }
    }

    private data class CliSendResultPayload(
        val msgId: String,
        val accepted: Boolean,
        val applicationDelivered: Boolean,
        val toAgentId: String,
        val error: String?,
        val reason: String?,
    ) {
        companion object {
            fun decode(json: JsonObject, fallbackToAgentId: String): CliSendResultPayload {
                fun requiredString(name: String): String = json[name]?.jsonPrimitive?.contentOrNull
                    ?: error("missing or invalid $name")
                fun requiredBoolean(name: String): Boolean = json[name]?.jsonPrimitive?.booleanOrNull
                    ?: error("missing or invalid $name")
                val msgId = requiredString("msgId")
                val accepted = requiredBoolean("accepted")
                val delivered = requiredBoolean("applicationDelivered")
                if (!accepted && delivered) error("contradictory accepted/applicationDelivered")
                return CliSendResultPayload(
                    msgId = msgId,
                    accepted = accepted,
                    applicationDelivered = delivered,
                    toAgentId = json["toAgentId"]?.jsonPrimitive?.contentOrNull ?: fallbackToAgentId,
                    error = json["error"]?.jsonPrimitive?.contentOrNull,
                    reason = json["reason"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    /**
     * The CLI exits 1 on both Unaddressable and Failed. The JSON stdout's
     * `error` field distinguishes them. If the JSON isn't there (e.g. the
     * CLI crashed before printing), collapse to Failed with the stderr tail.
     */
    private fun parseFailureFromStderr(
        stderr: String,
        stdout: String,
        toAgentId: String,
    ): IrohCliSendResult {
        val trimmedStdout = stdout.trim()
        if (trimmedStdout.isNotEmpty()) {
            val parsed = parseCliResultJson(trimmedStdout, msgId = "", toAgentId = toAgentId)
            if (parsed is IrohCliSendResult.Unaddressable || parsed is IrohCliSendResult.Failed || parsed is IrohCliSendResult.Accepted) {
                return parsed
            }
        }
        if (trimmedStdout.contains("\"error\":\"unaddressable\"")) {
            return IrohCliSendResult.Unaddressable(
                toAgentId = toAgentId,
                reason = stderr.trim().take(MAX_REASON_LEN).ifEmpty { "unaddressable" },
            )
        }
        val reason = if (stderr.isNotBlank()) {
            stderr.trim().take(MAX_REASON_LEN)
        } else if (trimmedStdout.isNotEmpty()) {
            trimmedStdout.take(MAX_REASON_LEN)
        } else {
            "exit_1_no_output"
        }
        return IrohCliSendResult.Failed(
            toAgentId = toAgentId,
            reason = reason,
        )
    }

    companion object {
        /** Default invocation deadline; see class KDoc for the rationale. */
        const val DEFAULT_TIMEOUT_MS: Long = 90_000L

        /**
         * Cap on the failure reason string so a runaway CLI cannot dump
         * megabytes into our tool result. 1 KiB is plenty for a human-
         * readable cause line.
         */
        const val MAX_REASON_LEN: Int = 1024

        /**
         * Default binary path for the production wrapper distribution.
         * Production callers should pass an explicit binary path; this is
         * what the default [IrohCliRunner] falls back to when none is
         * provided at construction time.
         *
         * For the wrapper process running on the same host as the binary,
         * `meridian-iroh-wrapper` (the installable JVM distribution) is the
         * canonical answer once that distribution gains the `agent-message`
         * subcommand. Until then, operators use the `:cli:run` JavaExec
         * task or a custom-built CLI jar.
         */
        val DEFAULT_BINARY: String = File(
            System.getProperty("user.home"),
            ".local/bin/meridian",
        ).path
    }
}
