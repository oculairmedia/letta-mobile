package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.transport.appserver.applyAppServerDefaults
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
 * parked runtime approval (e.g. AskUserQuestion). The App Server gateway
 * supports it; Iroh and test gateways may not, so callers detect it via
 * `gateway as? DesktopApprovalSubmitter`. See letta-mobile-vilsn.8.
 */
interface DesktopApprovalSubmitter {
    suspend fun submitApproval(submission: DesktopApprovalSubmission)
}

/**
 * letta-mobile-lgns8.19: optional capability a [DesktopChatGateway] may implement
 * to REALLY abort an in-flight server turn (App Server `abort_message`), so the
 * bottom-bar stop tears the run down server-side instead of merely cancelling
 * the local collect job. Iroh and test gateways may not implement it, so callers
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
 * letta-mobile folder-settings #2: optional capability a [DesktopChatGateway]
 * may implement to read/change a conversation's working directory — the
 * folder the bundled local runtime's tools (bash, file edits, git) actually
 * operate in. Only the App Server-backed gateway supports it (the runtime
 * genuinely tracks this per agent+conversation, see `get_cwd_map` /
 * `runtime_start.cwd`); test gateways may not, so callers detect it
 * via `gateway as? DesktopWorkingDirectoryController`.
 */
interface DesktopWorkingDirectoryController {
    /**
     * The working directory currently in effect for [agentId]/[conversationId],
     * or null if it couldn't be determined (e.g. the request failed). Falls
     * back to the runtime's boot working directory when the conversation
     * hasn't been individually customized.
     */
    suspend fun currentWorkingDirectory(agentId: String, conversationId: String): String?

    /**
     * Changes the working directory for [agentId]/[conversationId] to [path]
     * by re-issuing `runtime_start` with `cwd` set — the same mechanism the
     * runtime uses to persist a directory change, so it survives reconnects
     * and is visible to a `get_cwd_map` from any other client. Returns true
     * on success.
     */
    suspend fun setWorkingDirectory(agentId: String, conversationId: String, path: String): Boolean
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
    val selectedSuggestionIds: List<String> = emptyList(),
    /** The parked request [selectedSuggestionIds] were offered by; see [com.letta.mobile.data.runtime.approvalIdForSuggestions]. */
    val suggestionBinding: com.letta.mobile.data.runtime.ApprovalBinding? = null,
)

/**
 * The desktop's Ktor client for reaching an App Server: the WebSocket transport
 * ([DesktopAppServerChatGatewayBuilder]) and the `/app-server-info` probe. It never
 * talks to a Letta REST server (letta-mobile-ckmub.4 removed that backend).
 */
fun createDesktopAppServerHttpClient(): HttpClient = HttpClient(CIO) {
    engine {
        https {
            trustManager = NativeTrustManager.trustManager
        }
    }
    install(ContentNegotiation) {
        json(desktopChatJson)
    }
    // The App Server WebSocket transport calls httpClient.webSocket(...), which
    // requires the plugin; letta-mobile-lgns8.21.7 requires the inbound frame
    // ceiling with it.
    install(WebSockets) { applyAppServerDefaults() }
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
    // App Server payloads carry explicit nulls for several non-nullable fields that
    // have defaults (e.g. agent.metadata). Coerce null -> default so agent /
    // conversation deserialization doesn't fail and agent names hydrate.
    coerceInputValues = true
}
