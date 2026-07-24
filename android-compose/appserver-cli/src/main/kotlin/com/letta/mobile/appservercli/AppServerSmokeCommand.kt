package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.appserver.runAppServerSmokeTurn
import io.ktor.client.engine.cio.CIO
import java.util.UUID
import kotlinx.coroutines.runBlocking

internal class AppServerSmokeCommand : CliktCommand(
    name = "app-server-smoke",
) {
    private val url by option(
        "--url",
        envvar = "APP_SERVER_TEST_URL",
        help = "App Server base WS URL, for example ws://127.0.0.1:4500.",
    ).required()

    private val token by option(
        "--token",
        envvar = "APP_SERVER_TEST_TOKEN",
        help = "Optional bearer token for non-loopback App Server hosts.",
    )

    private val agentId by option(
        "--agent",
        envvar = "APP_SERVER_TEST_AGENT_ID",
        help = "Agent ID to run against.",
    ).required()

    private val conversationId by option(
        "--conversation",
        envvar = "APP_SERVER_TEST_CONVERSATION_ID",
        help = "Conversation ID to run against.",
    ).required()

    private val message by option(
        "--message",
        "-m",
        help = "User message text to send.",
    ).required()

    private val timeoutMs by option("--timeout-ms")
        .long()
        .default(120_000)

    override fun run() = runBlocking {
        if (timeoutMs <= 0) throw UsageError("--timeout-ms must be > 0")

        runAppServerSmokeTurn(
            engineFactory = CIO,
            identityPrefix = "host-cli",
            url = url,
            token = token,
            agentId = agentId,
            conversationId = conversationId,
            message = message,
            timeoutMs = timeoutMs,
            newToken = { UUID.randomUUID().toString() },
            emit = ::println,
        )
    }
}
