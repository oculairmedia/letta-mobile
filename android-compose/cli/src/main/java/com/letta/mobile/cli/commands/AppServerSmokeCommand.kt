package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

        val httpClient = HttpClient(OkHttp) {
            install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = timeoutMs
            }
        }

        try {
            withTimeout(timeoutMs) {
                coroutineScope {
                    val transport = KtorAppServerWebSocketTransport(
                        httpClient = httpClient,
                        baseUrl = url,
                        scope = this,
                        bearerToken = token,
                    )
                    val engine = AppServerTurnEngine(
                        client = DefaultAppServerClient(transport, requestTimeoutMs = timeoutMs),
                        requestIdFactory = { "cli-${UUID.randomUUID()}" },
                    )

                    try {
                        println("[app-server] connect $url")
                        engine.runTurn(
                            TurnCommand(
                                backendId = BackendId("app-server-cli"),
                                runtimeId = RuntimeId("app-server-cli"),
                                agentId = AgentId(agentId),
                                conversationId = ConversationId(conversationId),
                                input = TurnInput.UserMessage(
                                    localMessageId = "cli-${UUID.randomUUID()}",
                                    text = message,
                                ),
                            ),
                        ).collect { event ->
                            println(event.payload.toCliLine())
                        }
                    } finally {
                        transport.close()
                    }
                }
            }
        } finally {
            httpClient.close()
        }
    }
}

private fun RuntimeEventPayload.toCliLine(): String =
    when (this) {
        is RuntimeEventPayload.RunLifecycleChanged -> "[lifecycle] $status"
        is RuntimeEventPayload.RemoteStreamFrame -> "[stream] ${body.take(MAX_FRAME_CHARS)}"
        is RuntimeEventPayload.ExternalTransportFrame -> "[frame] ${body.take(MAX_FRAME_CHARS)}"
        is RuntimeEventPayload.ToolCallObserved -> "[tool-call] ${toolName.value} ${toolCallId.value}"
        is RuntimeEventPayload.ToolReturnObserved -> "[tool-return] $status ${toolCallId.value}"
        is RuntimeEventPayload.ApprovalRequested -> "[approval-request] ${request.toolName.value} ${request.callId.value}"
        is RuntimeEventPayload.ApprovalResolved -> "[approval-resolved] ${decision.decision} ${decision.callId.value}"
        is RuntimeEventPayload.MemFsCommitObserved -> "[memfs] commit"
        is RuntimeEventPayload.AgentFileImported -> "[agent-file] imported ${file.displayName}"
        is RuntimeEventPayload.AgentFileExported -> "[agent-file] exported ${file.displayName}"
        else -> "[event] ${toString().take(MAX_FRAME_CHARS)}"
    }

private const val MAX_FRAME_CHARS = 1_000
