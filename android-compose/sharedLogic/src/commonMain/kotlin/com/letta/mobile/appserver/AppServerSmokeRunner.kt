package com.letta.mobile.appserver

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.appserver.applyAppServerFrameLimits
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * The single turn a smoke run sends through a Letta App Server: which host to
 * reach ([url]/[token]), the target ([agentId]/[conversationId]), the [message]
 * text, and the [timeoutMs] budget. These cohesive request parameters travel as
 * one value so the runner and its two CLI call sites thread an identical turn
 * shape (audit P1.6 / gn7kr.12 — CodeScene #1012 arg-count cleanup).
 */
data class AppServerSmokeSpec(
    val url: String,
    val token: String?,
    val agentId: String,
    val conversationId: String,
    val message: String,
    val timeoutMs: Long,
)

/**
 * Send exactly one turn through a running Letta App Server and stream each
 * observed runtime event back as a printable CLI line.
 *
 * Shared by the `:cli` (`app-server-smoke`) and `:appserver-cli`
 * (`app-server-smoke`) entry points (audit P1.6 / gn7kr.12). The two binaries
 * differ only in their Ktor HTTP engine (OkHttp on `:cli`, CIO on
 * `:appserver-cli`) and their identity prefix (`cli` vs `host-cli`); both of
 * those genuine differences are explicit parameters rather than duplicated,
 * silently-drifting source. The turn's request parameters travel together in
 * [spec].
 *
 * @param engineFactory platform Ktor engine (e.g. `OkHttp`, `CIO`).
 * @param identityPrefix short marker embedded in backend/runtime/request ids so
 *   server-side logs can tell the two binaries apart ("cli" or "host-cli").
 * @param spec the host, target, message, and timeout for this one turn.
 * @param newToken supplies a fresh unique token per request id (e.g. a UUID).
 * @param emit receives one formatted line per runtime event.
 */
suspend fun runAppServerSmokeTurn(
    engineFactory: HttpClientEngineFactory<*>,
    identityPrefix: String,
    spec: AppServerSmokeSpec,
    newToken: () -> String,
    emit: (String) -> Unit,
) {
    val timeoutMs = spec.timeoutMs
    val httpClient = HttpClient(engineFactory) {
        install(WebSockets) { applyAppServerFrameLimits() }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = timeoutMs
        }
    }

    httpClient.use {
        withTimeout(timeoutMs.milliseconds) {
            coroutineScope {
                val transport = KtorAppServerWebSocketTransport(
                    httpClient = httpClient,
                    baseUrl = spec.url,
                    scope = this,
                    bearerToken = spec.token,
                )
                val engine = AppServerTurnEngine(
                    client = DefaultAppServerClient(transport, requestTimeoutMs = timeoutMs),
                    requestIdFactory = { "$identityPrefix-${newToken()}" },
                )

                try {
                    emit("[app-server] connect ${spec.url}")
                    engine.runTurn(
                        TurnCommand(
                            backendId = BackendId("app-server-$identityPrefix"),
                            runtimeId = RuntimeId("app-server-$identityPrefix"),
                            agentId = AgentId(spec.agentId),
                            conversationId = ConversationId(spec.conversationId),
                            input = TurnInput.UserMessage(
                                localMessageId = "$identityPrefix-${newToken()}",
                                text = spec.message,
                            ),
                        ),
                    ).collect { event ->
                        emit(event.payload.toCliLine())
                    }
                } finally {
                    transport.close()
                }
            }
        }
    }
}

/**
 * letta-mobile-8xxzv: E2E probe for the KEYED turn lease.
 *
 * Drives TWO turns through ONE [AppServerTurnEngine] on ONE `/ws` connection,
 * on two different `{agent_id, conversation_id}` runtimes, CONCURRENTLY. Before
 * the keyed lease the second turn could not even start: the engine held a single
 * process-wide lease and threw "An App Server turn is already active". letta-code
 * (0.29.9 / 0.29.12) keeps a per-conversation TurnLifecycle and runs runtimes in
 * parallel, so both turns must stream and both must reach a terminal lifecycle.
 *
 * Each emitted line is prefixed with the conversation it belongs to, so the
 * transcript itself proves the interleaving and the two terminals.
 *
 * @param spec host + first conversation (its [AppServerSmokeSpec.conversationId]).
 * @param secondConversationId the second runtime's conversation id.
 * @param secondAgentId agent for the second runtime (defaults to the first).
 */
suspend fun runAppServerConcurrentSmokeTurns(
    engineFactory: HttpClientEngineFactory<*>,
    identityPrefix: String,
    spec: AppServerSmokeSpec,
    secondConversationId: String,
    secondAgentId: String = spec.agentId,
    newToken: () -> String,
    emit: (String) -> Unit,
) {
    val timeoutMs = spec.timeoutMs
    val httpClient = HttpClient(engineFactory) {
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = timeoutMs
        }
    }

    httpClient.use {
        withTimeout(timeoutMs.milliseconds) {
            coroutineScope {
                val transport = KtorAppServerWebSocketTransport(
                    httpClient = httpClient,
                    baseUrl = spec.url,
                    scope = this,
                    bearerToken = spec.token,
                )
                val engine = AppServerTurnEngine(
                    client = DefaultAppServerClient(transport, requestTimeoutMs = timeoutMs),
                    requestIdFactory = { "$identityPrefix-${newToken()}" },
                )

                fun command(agentId: String, conversationId: String) = TurnCommand(
                    backendId = BackendId("app-server-$identityPrefix"),
                    runtimeId = RuntimeId("app-server-$identityPrefix"),
                    agentId = AgentId(agentId),
                    conversationId = ConversationId(conversationId),
                    input = TurnInput.UserMessage(
                        localMessageId = "$identityPrefix-${newToken()}",
                        text = spec.message,
                    ),
                )

                suspend fun drive(label: String, agentId: String, conversationId: String) {
                    runCatching {
                        engine.runTurn(command(agentId, conversationId)).collect { event ->
                            emit("[$label] ${event.payload.toCliLine()}")
                        }
                    }.onFailure { error ->
                        emit("[$label] [error] ${error::class.simpleName}: ${error.message}")
                        throw error
                    }
                }

                try {
                    emit("[app-server] connect ${spec.url}")
                    emit("[app-server] concurrent runtimes: ${spec.agentId}/${spec.conversationId} + $secondAgentId/$secondConversationId")
                    val first = async { drive("conv-1", spec.agentId, spec.conversationId) }
                    val second = async { drive("conv-2", secondAgentId, secondConversationId) }
                    first.await()
                    second.await()
                    emit("[app-server] both concurrent turns reached a terminal")
                } finally {
                    transport.close()
                }
            }
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
