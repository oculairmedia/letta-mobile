package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long

internal class AppServerCommand : CliktCommand(name = "app-server") {
    override fun run() = Unit
}

internal class AppServerSmokeCommand : CliktCommand(name = "smoke") {
    private val url by option(
        "--url",
        envvar = "APP_SERVER_TEST_URL",
        help = "App Server WebSocket URL, for example ws://127.0.0.1:8283 or http://127.0.0.1:8283.",
    )

    private val token by option(
        "--token",
        envvar = "APP_SERVER_TOKEN",
        help = "Optional ws-auth token for a protected App Server.",
    )

    private val message by option(
        "--message",
        "-m",
        help = "Single user message to send during the smoke turn.",
    ).default("hello from meridian cli")

    private val cwd by option(
        "--cwd",
        help = "Runtime working directory passed to runtime_start.",
    ).default(".")

    private val timeoutMs by option(
        "--timeout-ms",
        help = "Overall smoke timeout in milliseconds.",
    ).long().default(120_000)

    private val printPlan by option(
        "--print-plan",
        help = "Print the planned App Server smoke sequence without opening a socket.",
    ).flag(default = false)

    override fun run() {
        val plan = buildAppServerSmokePlan(
            rawUrl = url,
            hasToken = token != null,
            message = message,
            cwd = cwd,
            timeoutMs = timeoutMs,
        )
        if (printPlan) {
            println(plan.render())
            return
        }
        if (url.isNullOrBlank()) {
            throw UsageError("Missing App Server URL. Pass --url or set APP_SERVER_TEST_URL.")
        }
        throw UsageError(
            "App Server smoke execution is blocked until the shared typed client from " +
                "letta-mobile-ph9ws.8 is available in this worktree. Re-run with --print-plan " +
                "to inspect the intended runtime_start -> input -> stream -> stop flow."
        )
    }
}

internal data class AppServerSmokePlan(
    val url: String?,
    val hasToken: Boolean,
    val message: String,
    val cwd: String,
    val timeoutMs: Long,
) {
    fun render(): String = buildString {
        appendLine("[app-server] smoke plan")
        appendLine("  url=${url ?: "<APP_SERVER_TEST_URL>"}")
        appendLine("  auth=${if (hasToken) "bearer token" else "none"}")
        appendLine("  cwd=$cwd")
        appendLine("  timeoutMs=$timeoutMs")
        appendLine("  frames:")
        appendLine("    1. connect App Server WebSocket")
        appendLine("    2. runtime_start with cwd")
        appendLine("    3. input user message (${message.length} chars)")
        appendLine("    4. print stream_delta/lifecycle frames")
        appendLine("    5. stop/close cleanly")
        appendLine("  dependency=letta-mobile-ph9ws.8 shared typed App Server client")
    }.trimEnd()
}

internal fun buildAppServerSmokePlan(
    rawUrl: String?,
    hasToken: Boolean,
    message: String,
    cwd: String,
    timeoutMs: Long,
): AppServerSmokePlan =
    AppServerSmokePlan(
        url = rawUrl?.takeIf { it.isNotBlank() }?.let(::normalizeAppServerWsUrl),
        hasToken = hasToken,
        message = message,
        cwd = cwd,
        timeoutMs = timeoutMs,
    )

internal fun normalizeAppServerWsUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim().trimEnd('/')
    return when {
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        else -> "ws://$trimmed"
    }
}
