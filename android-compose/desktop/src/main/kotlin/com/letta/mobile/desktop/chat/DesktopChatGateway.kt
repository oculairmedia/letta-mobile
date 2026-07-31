package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.http.LettaHttpChatGateway
import com.letta.mobile.data.transport.appserver.applyAppServerFrameLimits
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import dev.nucleusframework.nativessl.NativeTrustManager

typealias DesktopChatGateway = ChatGateway

/**
 * Optional capability a [DesktopChatGateway] may implement to answer / dismiss a
 * parked runtime approval (e.g. AskUserQuestion). Only the App Server-backed
 * gateway supports it; HTTP-only / demo gateways don't, so callers detect it via
 * `gateway as? DesktopApprovalSubmitter`. See letta-mobile-vilsn.8.
 */
interface DesktopApprovalSubmitter {
    suspend fun submitApproval(submission: DesktopApprovalSubmission)
}

/**
 * letta-mobile-lgns8.19: optional capability a [DesktopChatGateway] may implement
 * to REALLY abort an in-flight server turn (App Server `abort_message`), so the
 * bottom-bar stop tears the run down server-side instead of merely cancelling
 * the local collect job. HTTP-only / demo gateways don't implement it, so callers
 * detect it via `gateway as? DesktopTurnAborter` and fall back to a local clear.
 */
interface DesktopTurnAborter {
    /**
     * Sends an abort for [conversationId]'s active turn. Returns true when the
     * abort was actually dispatched to the server (the server then emits its own
     * terminal frame, which is what releases the UI); false when there was no
     * live runtime to abort.
     */
    suspend fun abortConversationTurn(conversationId: String): Boolean
}

/**
 * A decision for a parked approval. [reason] carries an AskUserQuestion answer
 * when encoded via [com.letta.mobile.data.model.AskUserQuestion.encodeAnswerReason];
 * otherwise it's a plain allow/deny message.
 */
data class DesktopApprovalSubmission(
    val agentId: String,
    val conversationId: String,
    val requestId: String,
    val toolCallId: String?,
    val approve: Boolean,
    val reason: String?,
)

/**
 * Desktop binding for the shared [LettaHttpChatGateway]. The platform-neutral
 * conversations/messages/streaming HTTP logic lives in commonMain; the desktop
 * module supplies only the JVM Ktor CIO engine (letta-mobile-mqzkc).
 */
class DesktopLettaHttpChatGateway(
    config: LettaConfig,
    httpClient: HttpClient = createDesktopLettaHttpClient(),
) : LettaHttpChatGateway(config = config, httpClient = httpClient)

fun createDesktopLettaHttpClient(): HttpClient = HttpClient(CIO) {
    engine {
        https {
            trustManager = NativeTrustManager.trustManager
        }
    }
    install(ContentNegotiation) {
        json(desktopChatJson)
    }
    // This client also backs the desktop App Server WebSocket transport
    // (DesktopAppServerControllerGatewayFactory.buildWebSocketTransport), which
    // calls httpClient.webSocket(...) — that requires the plugin, and
    // letta-mobile-lgns8.21.7 requires the inbound frame ceiling with it.
    install(WebSockets) { applyAppServerFrameLimits() }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}

internal val desktopChatJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    // The Letta API returns explicit nulls for several non-nullable fields that
    // have defaults (e.g. agent.metadata). Coerce null -> default so agent /
    // conversation deserialization doesn't fail and agent names hydrate.
    coerceInputValues = true
}
