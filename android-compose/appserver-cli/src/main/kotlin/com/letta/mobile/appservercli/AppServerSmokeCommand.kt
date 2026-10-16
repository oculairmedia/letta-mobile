package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.appserver.AppServerSmokeSpec
import com.letta.mobile.appserver.runAppServerConcurrentSmokeTurns
import com.letta.mobile.appserver.runAppServerSmokeTurn
import com.letta.mobile.data.model.MessageContentPart
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.util.Base64
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

    // letta-mobile-8xxzv: when set, TWO turns are driven concurrently through one
    // engine on one socket — the E2E proof of the per-{agent, conversation} lease.
    private val secondConversationId by option(
        "--second-conversation",
        envvar = "APP_SERVER_TEST_CONVERSATION_ID_2",
        help = "Second conversation ID; when set, two turns run CONCURRENTLY through one engine.",
    )

    private val secondAgentId by option(
        "--second-agent",
        envvar = "APP_SERVER_TEST_AGENT_ID_2",
        help = "Agent for --second-conversation (defaults to --agent).",
    )

    private val images by option(
        "--image",
        help = "Image file (png/jpeg/gif/webp) sent with the message as the app sends a photo; repeatable.",
    ).file(mustExist = true, canBeDir = false).multiple()

    private val timeoutMs by option("--timeout-ms")
        .long()
        .default(120_000)

    override fun run() = runBlocking {
        if (timeoutMs <= 0) throw UsageError("--timeout-ms must be > 0")

        val spec = AppServerSmokeSpec(
            url = url,
            token = token,
            agentId = agentId,
            conversationId = conversationId,
            message = message,
            timeoutMs = timeoutMs,
            images = images.map(::imagePart),
        )
        val second = secondConversationId
        if (second != null) {
            runAppServerConcurrentSmokeTurns(
                engineFactory = CIO,
                identityPrefix = "host-cli",
                spec = spec,
                secondConversationId = second,
                secondAgentId = secondAgentId ?: agentId,
                newToken = { UUID.randomUUID().toString() },
                emit = ::println,
            )
            return@runBlocking
        }
        runAppServerSmokeTurn(
            engineFactory = CIO,
            identityPrefix = "host-cli",
            spec = spec,
            newToken = { UUID.randomUUID().toString() },
            emit = ::println,
        )
    }
}

/** [file] as the app's inline image part; the media type comes from the extension. */
internal fun imagePart(file: File): MessageContentPart.Image {
    val mediaType = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> throw UsageError("--image ${file.name}: expected .png, .jpg, .jpeg, .gif or .webp")
    }
    return MessageContentPart.Image(base64 = Base64.getEncoder().encodeToString(file.readBytes()), mediaType = mediaType)
}
