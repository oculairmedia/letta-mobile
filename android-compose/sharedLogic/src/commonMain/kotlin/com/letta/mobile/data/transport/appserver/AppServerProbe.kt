package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a successful probe learned about the server. */
data class AppServerIdentity(
    val backend: String,
    val lettaCodeVersion: String,
    val protocolVersion: Int,
)

/**
 * The classified outcome of an App Server "Test connection" probe (letta-mobile-bzvro.1, F01).
 *
 * [Authentication] and [Incompatible] are terminal: retrying the same URL and token cannot succeed,
 * so a reconnect loop must stop on them (F02). [Unavailable] is transient.
 */
sealed interface AppServerProbeResult {
    data class Ok(val identity: AppServerIdentity) : AppServerProbeResult

    /** The server rejected the token (HTTP 401/403, or a failed auth exchange). */
    data class Authentication(val detail: String) : AppServerProbeResult

    /** The server answered but is not an App Server this client can use. */
    data class Incompatible(val reason: String) : AppServerProbeResult

    /** The server could not be reached, timed out, or failed for a reason that may pass. */
    data class Unavailable(val detail: String) : AppServerProbeResult

    /** True when retrying with the same settings cannot help. */
    val isTerminal: Boolean get() = this is Authentication || this is Incompatible
}

/**
 * Tests an App Server without opening a WebSocket: `GET /app-server-info` with the bearer token.
 * The App Server allows one control session, so a probe must never dial `/ws` and disturb the
 * session that is already open.
 *
 * Classification:
 * - 401/403 → [AppServerProbeResult.Authentication]
 * - 404, 426, an unreadable body, an unsuccessful info response, the wrong `protocol_version`, or a
 *   missing required capability → [AppServerProbeResult.Incompatible]
 * - a network error, timeout or any other status → [AppServerProbeResult.Unavailable]
 */
class AppServerProbe(
    private val http: HttpClient,
    private val requirement: AppServerCompatibilityRequirement = DEFAULT_REQUIREMENT,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    suspend fun probe(baseUrl: String, bearerToken: String? = null): AppServerProbeResult {
        val origin = try {
            AppServerDiscovery.httpOrigin(baseUrl.trim())
        } catch (e: IllegalArgumentException) {
            return AppServerProbeResult.Incompatible(e.message ?: "Not an App Server URL")
        }
        return try {
            withTimeout(timeout) { fetchAndClassify(origin, bearerToken?.trim()?.takeIf { it.isNotEmpty() }) }
        } catch (_: TimeoutCancellationException) {
            AppServerProbeResult.Unavailable("No answer within ${timeout.inWholeSeconds} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppServerProbeResult.Unavailable(e.message ?: e::class.simpleName ?: "Connection failed")
        }
    }

    private suspend fun fetchAndClassify(origin: String, bearerToken: String?): AppServerProbeResult {
        val response = http.get("$origin/app-server-info") {
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        val status = response.status.value
        classifyStatus(status)?.let { return it }
        val decoded = try {
            AppServerDiscovery.decodeAppServerInfoBody(response.bodyAsText())
        } catch (e: SerializationException) {
            return AppServerProbeResult.Incompatible("The server's info response is unreadable: ${e.message.orEmpty().take(MAX_DETAIL)}")
        } catch (e: IllegalArgumentException) {
            return AppServerProbeResult.Incompatible("The server's info response is unreadable: ${e.message.orEmpty().take(MAX_DETAIL)}")
        }
        return classify(decoded, requirement)
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 10.seconds
        private const val MAX_DETAIL = 160

        /**
         * What this client needs from an App Server: protocol 1, the four management
         * capabilities, and the single bidirectional socket (`split_channels` off).
         */
        val DEFAULT_REQUIREMENT = AppServerCompatibilityRequirement(
            protocolVersion = 1,
            requiredCapabilities = setOf(
                "agent_management",
                "conversation_management",
                "memory_management",
                "runtime_start",
            ),
            requiredDisabledCapabilities = setOf("split_channels"),
        )

        /** Null when the status carries a body worth decoding. */
        internal fun classifyStatus(status: Int): AppServerProbeResult? = when {
            status == 401 || status == 403 -> AppServerProbeResult.Authentication("The server rejected the access token (HTTP $status)")
            status == 404 -> AppServerProbeResult.Incompatible("The server has no /app-server-info endpoint (HTTP 404); it is not an App Server")
            status == 426 -> AppServerProbeResult.Incompatible("The server requires a protocol this client does not speak (HTTP 426)")
            status in 200..299 -> null
            else -> AppServerProbeResult.Unavailable("The server answered HTTP $status")
        }

        /**
         * Classifies an `app_server_info_response`, from HTTP or from the socket. Usable for Iroh
         * endpoints, where the probe is the `app_server_info` request over an authenticated dial.
         */
        fun classify(
            response: AppServerInboundFrame.AppServerInfoResponse,
            requirement: AppServerCompatibilityRequirement = DEFAULT_REQUIREMENT,
        ): AppServerProbeResult {
            if (!response.success) {
                return AppServerProbeResult.Incompatible(response.error ?: "The server reported an unsuccessful info response")
            }
            val info = response.info
                ?: return AppServerProbeResult.Incompatible("The server's info response has no server information")
            return try {
                info.requireCompatibleWith(requirement)
                AppServerProbeResult.Ok(
                    AppServerIdentity(
                        backend = info.backend.orEmpty(),
                        lettaCodeVersion = info.lettaCodeVersion.orEmpty(),
                        protocolVersion = info.protocolVersion ?: requirement.protocolVersion,
                    ),
                )
            } catch (e: IllegalStateException) {
                AppServerProbeResult.Incompatible(e.message ?: "The server is not compatible")
            }
        }

        /**
         * Probes over an already-dialled [client] (for example an authenticated Iroh session):
         * sends `app_server_info` and classifies the answer.
         */
        suspend fun probeClient(
            client: AppServerClient,
            requestId: String,
            requirement: AppServerCompatibilityRequirement = DEFAULT_REQUIREMENT,
            timeout: Duration = DEFAULT_TIMEOUT,
        ): AppServerProbeResult = try {
            withTimeout(timeout) { classify(client.appServerInfo(AppServerCommand.AppServerInfo(requestId)), requirement) }
        } catch (_: TimeoutCancellationException) {
            AppServerProbeResult.Unavailable("No answer within ${timeout.inWholeSeconds} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            AppServerProbeResult.Incompatible(e.message ?: "The server does not answer app_server_info")
        } catch (e: Exception) {
            AppServerProbeResult.Unavailable(e.message ?: e::class.simpleName ?: "Request failed")
        }
    }
}
