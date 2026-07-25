package com.letta.mobile.appserver

import java.net.URI

/**
 * Thrown when an [AppServerServeSpec] cannot be turned into a valid host command
 * (blank required value, malformed listen URL, missing auth for a non-loopback
 * host, …). This is a transport-neutral exception so the shared builder does not
 * depend on any CLI framework; the CLI entry points catch it and re-raise it as a
 * clikt `UsageError` for friendly rendering.
 */
class AppServerServeSpecException(message: String) : IllegalArgumentException(message)

/**
 * Declarative description of a host `letta app-server` invocation. Shared by the
 * `:cli` (`meridian app-server-serve`) and `:appserver-cli`
 * (`meridian-app-server app-server-serve`) entry points so the arg-building and
 * validation logic lives in exactly one place (audit P1.6 / gn7kr.12).
 */
data class AppServerServeSpec(
    val listen: String = DEFAULT_APP_SERVER_LISTEN,
    val lettaCommand: String = DEFAULT_LETTA_COMMAND,
    val lettaArguments: List<String> = emptyList(),
    val wsAuth: String? = null,
    val wsTokenFile: String? = null,
    val wsTokenSha256: String? = null,
    val wsSharedSecretFile: String? = null,
    val wsIssuer: String? = null,
    val wsAudience: String? = null,
    val wsMaxClockSkewSeconds: Long? = null,
)

/**
 * Build the process command (argv) that launches the host Letta App Server for the
 * given [spec]. Throws [AppServerServeSpecException] on any invalid input.
 */
fun buildAppServerServeCommand(spec: AppServerServeSpec): List<String> {
    val command = mutableListOf<String>()

    command += requireNonBlank(spec.lettaCommand, "--letta-command")
    spec.lettaArguments.forEachIndexed { index, argument ->
        command += requireNonBlank(argument, "--letta-arg #${index + 1}")
    }
    command += "app-server"
    command += "--listen"
    val listen = requireNonBlank(spec.listen, "--listen")
    requireRemoteAuthForNonLoopback(listen, spec.wsAuth)
    command += listen

    spec.wsAuth?.let {
        val authMode = requireNonBlank(it, "--ws-auth")
        if (authMode != APP_SERVER_AUTH_CAPABILITY_TOKEN && authMode != APP_SERVER_AUTH_SIGNED_BEARER_TOKEN) {
            throw AppServerServeSpecException(
                "--ws-auth must be $APP_SERVER_AUTH_CAPABILITY_TOKEN or $APP_SERVER_AUTH_SIGNED_BEARER_TOKEN",
            )
        }
        command += "--ws-auth"
        command += authMode
    }
    appendOption(command, "--ws-token-file", spec.wsTokenFile)
    appendOption(command, "--ws-token-sha256", spec.wsTokenSha256)
    appendOption(command, "--ws-shared-secret-file", spec.wsSharedSecretFile)
    appendOption(command, "--ws-issuer", spec.wsIssuer)
    appendOption(command, "--ws-audience", spec.wsAudience)
    spec.wsMaxClockSkewSeconds?.let {
        if (it <= 0) throw AppServerServeSpecException("--ws-max-clock-skew-seconds must be > 0")
        command += "--ws-max-clock-skew-seconds"
        command += it.toString()
    }

    return command
}

/** Render an argv as a shell-ish single line, quoting arguments that contain whitespace. */
fun formatProcessCommand(command: List<String>): String =
    command.joinToString(" ") { argument ->
        if (argument.isEmpty()) {
            "\"\""
        } else if (argument.any { it.isWhitespace() || it == '"' }) {
            "\"${argument.replace("\"", "\\\"")}\""
        } else {
            argument
        }
    }

private fun requireRemoteAuthForNonLoopback(listen: String, wsAuth: String?) {
    val uri = runCatching { URI(listen) }.getOrElse {
        throw AppServerServeSpecException("--listen must be a valid ws:// URL")
    }
    if (uri.scheme != "ws" || uri.host.isNullOrBlank()) {
        throw AppServerServeSpecException("--listen must be a valid ws:// URL")
    }
    if (!uri.host.isLoopbackHost() && wsAuth.isNullOrBlank()) {
        throw AppServerServeSpecException("--ws-auth is required when --listen is not a loopback host")
    }
}

private fun String.isLoopbackHost(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    return normalized == "localhost" ||
        normalized == "127.0.0.1" ||
        normalized == "::1" ||
        normalized.startsWith("127.")
}

private fun appendOption(command: MutableList<String>, name: String, value: String?) {
    value?.let {
        command += name
        command += requireNonBlank(it, name)
    }
}

private fun requireNonBlank(value: String, optionName: String): String {
    if (value.isBlank()) throw AppServerServeSpecException("$optionName must not be blank")
    return value
}

const val DEFAULT_APP_SERVER_LISTEN = "ws://127.0.0.1:4500"
const val DEFAULT_LETTA_COMMAND = "letta"
const val APP_SERVER_AUTH_CAPABILITY_TOKEN = "capability-token"
const val APP_SERVER_AUTH_SIGNED_BEARER_TOKEN = "signed-bearer-token"
