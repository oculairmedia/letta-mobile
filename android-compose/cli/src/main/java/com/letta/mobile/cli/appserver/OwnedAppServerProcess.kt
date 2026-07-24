package com.letta.mobile.cli.appserver

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * lgns8.18 (Path A — owned process, desktop/bundled): spawn letta.js's App Server
 * as a JVM-owned child process and connect the existing WebSocket transport to the
 * loopback port it binds.
 *
 * This gives DETERMINISTIC lifecycle ownership — the child is ours, its death is a
 * [Process] exit code / stdout EOF, not an ambiguous peer disconnect — WITHOUT
 * changing the App-Server-v2 wire protocol (still WS, just to a self-spawned
 * loopback child instead of an external peer service). It collapses the
 * "is it alive / is it the one we think / did the write land" coordination tax that
 * motivated the peer-process fencing/supervisor/reconnect machinery.
 *
 * Intended for the bundled desktop app where there is NO external supervisor. On the
 * systemd server the external-ws path (a separately-supervised meridian-appserver)
 * stays the default: systemd is the better supervisor there (restart policy, cgroup
 * memory caps, journald) and the wrapper/appserver split lets the wrapper redeploy
 * without dropping live conversations — see the lgns8.18 bead for that rationale.
 *
 * The child is launched with `--listen ws://127.0.0.1:0` (ephemeral port) and
 * announces its bound URL on stdout ("Listening on ws://127.0.0.1:<port>"); [spawn]
 * blocks until that line, or fails if the child exits / times out first.
 *
 * KNOWN LIMITATION: on a hard parent kill (SIGKILL / JVM crash) the JVM cannot run a
 * shutdown hook, so the child can orphan and hold its (ephemeral) port. Graceful
 * shutdown is covered by [close] + a shutdown hook at the call site; a
 * PR_SET_PDEATHSIG / process-group teardown for the SIGKILL case is a follow-up.
 */
class OwnedAppServerProcess private constructor(
    val process: Process,
    /** The `ws://127.0.0.1:<port>` base URL the child bound — feed to KtorAppServerWebSocketTransport. */
    val wsBaseUrl: String,
) {
    val isAlive: Boolean get() = process.isAlive

    /** Graceful stop (destroy → wait [graceMillis] → destroyForcibly). Idempotent. */
    fun close(graceMillis: Long = DEFAULT_GRACE_MILLIS) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 5_000L
        const val DEFAULT_READY_TIMEOUT_MILLIS = 30_000L
        private val LISTENING = Regex("""Listening on (ws://\S+)""")

        /**
         * Build the child command: `<lettaCommand> [extraArgs...] app-server --listen
         * ws://127.0.0.1:0`. The ephemeral port avoids conflicts in a bundled app; the
         * child echoes the resolved URL, which [spawn] parses.
         */
        fun buildCommand(lettaCommand: String, extraArgs: List<String> = emptyList()): List<String> =
            buildList {
                add(lettaCommand)
                addAll(extraArgs)
                add("app-server")
                add("--listen")
                add("ws://127.0.0.1:0")
            }

        /**
         * Spawn the child and block until it announces its listen URL. Throws
         * [IllegalStateException] if the child exits or fails to announce within
         * [readyTimeoutMillis]. stdout (after the announce) and stderr are drained on
         * daemon threads to [log] so the child's pipes never fill and block it.
         */
        fun spawn(
            command: List<String>,
            workingDir: File? = null,
            environment: Map<String, String> = emptyMap(),
            readyTimeoutMillis: Long = DEFAULT_READY_TIMEOUT_MILLIS,
            log: (String) -> Unit = {},
        ): OwnedAppServerProcess {
            val pb = ProcessBuilder(command)
            workingDir?.let { pb.directory(it) }
            if (environment.isNotEmpty()) pb.environment().putAll(environment)
            // Keep stdout (protocol announce + any protocol output) SEPARATE from
            // stderr (human logs) — never redirectErrorStream, or logs corrupt the
            // announce parse (and, in a true-stdio future, the frame stream).
            val process = pb.start()
            startDrain("app-server:err", process.errorStream.bufferedReader(), log)

            // Read stdout on a daemon thread that pushes each line onto a queue and
            // drains for the child's whole life. The readiness scan then POLLS the
            // queue against a deadline — so a silent-but-alive child (blocking read)
            // can't defeat the readiness timeout, and stdout never fills/blocks.
            val queue = java.util.concurrent.LinkedBlockingQueue<Any>()
            val eof = Any()
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        log("[app-server] $line")
                        queue.put(line)
                    }
                }
                queue.put(eof)
            }.apply { isDaemon = true; name = "owned-app-server-stdout"; start() }

            val deadlineNanos = System.nanoTime() + readyTimeoutMillis * 1_000_000L
            var url: String? = null
            var sawEof = false
            while (true) {
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) break
                val item = queue.poll(remainingMs, TimeUnit.MILLISECONDS) ?: break // deadline
                if (item === eof) {
                    sawEof = true // stdout closed => the child is exiting/gone
                    break
                }
                val match = LISTENING.find(item as String)
                if (match != null) {
                    url = match.groupValues[1]
                    break
                }
            }
            if (url == null) {
                if (sawEof) {
                    // Let the exit be reaped so we can report the code (EOF slightly
                    // precedes isAlive flipping false — don't race it).
                    process.waitFor(2, TimeUnit.SECONDS)
                    val code = runCatching { process.exitValue() }.getOrNull()
                    process.destroyForcibly()
                    throw IllegalStateException("app-server child exited (code $code) before announcing a listen URL")
                }
                process.destroyForcibly()
                throw IllegalStateException("app-server child did not announce a listen URL within ${readyTimeoutMillis}ms")
            }
            return OwnedAppServerProcess(process, url)
        }

        private fun startDrain(name: String, reader: BufferedReader, log: (String) -> Unit) {
            Thread {
                runCatching { reader.forEachLine { log("[$name] $it") } }
            }.apply {
                isDaemon = true
                this.name = "owned-$name-drain"
                start()
            }
        }
    }
}
