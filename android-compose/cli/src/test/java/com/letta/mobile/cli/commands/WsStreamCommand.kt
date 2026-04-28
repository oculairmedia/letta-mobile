package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.WsBotClient
import com.letta.mobile.cli.runtime.WsMergeTracer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Open a WebSocket to lettabot's `/api/v1/agent-gateway`, send a chat
 * request, and print every chunk received from the gateway plus the
 * merge state after each one.
 *
 * This is the **Client Mode** path — the same one the Android app uses
 * when `letta-mobile-6p4o` (garbled streaming) reproduces. We reuse
 * `WsBotClient` verbatim from `:bot` so the wire behavior matches the
 * device exactly.
 */
class WsStreamCommand : CliktCommand(
    name = "wsstream",
    help = """
        Open a WebSocket to a lettabot gateway, send a message, and
        print every WsStreamEventMessage frame plus the merge state.
        This is the Client Mode path (the surface where SSE garbled
        streaming reproduces).
    """.trimIndent(),
) {
    private val baseUrl by option(
        "--base-url",
        envvar = "LETTABOT_BASE_URL",
        help = "lettabot HTTP/WS base URL (e.g. https://lettabot.oculair.ca or http://localhost:9000)."
    ).required()

    private val token by option(
        "--token",
        envvar = "LETTABOT_TOKEN",
        help = "Bearer token for the lettabot gateway. Optional if the server has auth disabled."
    ).default("")

    private val agentId by option(
        "--agent",
        envvar = "LETTABOT_AGENT_ID",
        help = "Agent ID to chat with."
    ).required()

    private val conversationId by option(
        "--conversation",
        envvar = "LETTABOT_CONVERSATION_ID",
        help = "Conversation ID to send into. If unset, lettabot picks one."
    ).default("")

    private val chatId by option(
        "--chat-id",
        help = "Optional chat_id to set in the WsSource envelope. Default 'cli-debug'."
    ).default("cli-debug")

    private val message by option(
        "-m", "--message",
        help = "User message text to send."
    ).required()

    private val forceNew by option(
        "--force-new",
        help = "Send session_start with force_new=true to make the gateway clear its persisted conversation map and start a fresh Letta conversation. Mirrors the mobile 'New chat' tap.",
    ).flag(default = false)

    override fun run() {
        val client = WsBotClient(
            baseUrl = baseUrl,
            apiKey = token.ifBlank { null },
        )

        try {
            runBlocking {
                println("[CLI] WS  connect $baseUrl/api/v1/agent-gateway")
                println("[CLI]   agent=$agentId  conversation=${conversationId.ifBlank { "<auto>" }}  force_new=$forceNew")
                println("[CLI]   message=\"${message.take(80)}${if (message.length > 80) "..." else ""}\"")
                println("[CLI] -----------------------------------------------------")

                val tracer = WsMergeTracer()
                val request = BotChatRequest(
                    message = message,
                    agentId = agentId,
                    chatId = chatId,
                    conversationId = conversationId.ifBlank { null },
                    forceNew = forceNew,
                )

                client.streamMessage(request).collect { chunk: BotStreamChunk ->
                    tracer.onChunk(chunk)
                }

                println("[CLI] -----------------------------------------------------")
                println("[CLI] WS  STREAM CLOSED")
                tracer.printSummary()
            }
        } finally {
            client.close()
        }
    }
}

