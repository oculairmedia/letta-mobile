package com.letta.mobile.data.system1

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The System 1 decision layer, as the rest of the app sees it.
 *
 * Every method is allowed to fail. A System 1 layer that can block a user's turn
 * is worse than no System 1 layer at all, so implementations degrade to a
 * neutral [System1Assessment.unavailable] rather than propagating errors.
 */
interface System1DecisionEngine {
    /** Full assessment: guard + route + triage + interaction in one forward pass. */
    suspend fun evaluate(request: System1EvaluateRequest): System1Assessment

    /** Safety preflight alone — cheaper when routing signals are not needed. */
    suspend fun guard(request: System1EvaluateRequest): System1GuardReport?

    /** Semantic turn-taking signals alone, for the interactive composer. */
    suspend fun interaction(request: System1EvaluateRequest): System1InteractionReport?

    /** Whether the service answered a health probe. */
    suspend fun isAvailable(): Boolean
}

/**
 * Ktor client for the local Laya System 1 service.
 *
 * Talks to `http://127.0.0.1:8771` by default — loopback, because the service is
 * an in-process-speed dependency rather than a network service.
 *
 * **Disabled unless a host opts in** ([Config.enabled]). The Python service is a
 * development harness for proving the concept on desktop, not a shipping
 * dependency; [System1ReflexGate] is the part that runs everywhere today. When
 * disabled, every method short-circuits before touching the network.
 *
 * The [config] timeout is a hard budget, not a hope: past it the call is
 * abandoned and the neutral fallback is returned. A slow System 1 has no value,
 * since the entire premise is that it costs less than the System 2 turn it is
 * deciding about.
 *
 * The [HttpClient] is supplied by the caller and is NOT closed here; this class
 * does not own it.
 */
class System1Client(
    private val httpClient: HttpClient,
    private val config: Config = Config(),
    private val json: Json = DEFAULT_JSON,
) : System1DecisionEngine {

    /**
     * @param baseUrl service origin; loopback by default.
     * @param timeoutMs budget for one call, including connect. 500ms is ~5x the
     *   measured CUDA round-trip, leaving room for a cold CPU fallback.
     * @param enabled **off by default.** The Laya service is a Python process
     *   with a 1.6GB checkpoint bound to loopback: desktop can host it, a phone
     *   cannot reach it. Until inference is hosted the same way on every target,
     *   this stays opt-in, and a host that does not opt in pays nothing — no
     *   process, no socket, no call. Desktop development sets this to true.
     */
    data class Config(
        val baseUrl: String = "http://127.0.0.1:8771",
        val timeoutMs: Long = 500L,
        val enabled: Boolean = false,
    )

    private val root = config.baseUrl.trimEnd('/')

    override suspend fun evaluate(request: System1EvaluateRequest): System1Assessment {
        if (!config.enabled) return System1Assessment.unavailable("System 1 disabled")
        if (request.text.isBlank()) return System1Assessment.unavailable("empty input")
        val body = json.encodeToString(System1EvaluateRequest.serializer(), request)
        val raw = postOrNull(Section.Evaluate, body)
            ?: return System1Assessment.unavailable("System 1 unreachable or over budget")
        return decodeOrNull(System1Assessment.serializer(), raw)
            ?: System1Assessment.unavailable("System 1 response was not decodable")
    }

    override suspend fun guard(request: System1EvaluateRequest): System1GuardReport? =
        section(Section.Guard, System1GuardReport.serializer(), request)

    override suspend fun interaction(request: System1EvaluateRequest): System1InteractionReport? =
        section(Section.Interaction, System1InteractionReport.serializer(), request)

    override suspend fun isAvailable(): Boolean {
        if (!config.enabled) return false
        val raw = withTimeoutOrNull(config.timeoutMs) {
            runCatchingNonCancellation {
                val response = httpClient.get("$root/health")
                if (response.status.isSuccess()) response.bodyAsText() else null
            }
        } ?: return false
        return raw.contains("\"ok\"")
    }

    private enum class Section(val path: String, val field: String) {
        Evaluate("/v1/system1/evaluate", ""),
        Guard("/v1/system1/guard", "guard"),
        Interaction("/v1/system1/interaction", "interaction"),
    }

    /** Single-set endpoints wrap their report in a named field; unwrap it. */
    private suspend fun <T> section(
        endpoint: Section,
        serializer: KSerializer<T>,
        request: System1EvaluateRequest,
    ): T? {
        if (!config.enabled || request.text.isBlank()) return null
        val body = json.encodeToString(System1EvaluateRequest.serializer(), request)
        val raw = postOrNull(endpoint, body) ?: return null
        val element = decodeOrNull(JsonElement.serializer(), raw)
        val section = (element as? JsonObject)?.get(endpoint.field) ?: return null
        return runCatchingNonCancellation { json.decodeFromJsonElement(serializer, section) }
    }

    private suspend fun postOrNull(endpoint: Section, body: String): String? =
        withTimeoutOrNull(config.timeoutMs) {
            runCatchingNonCancellation {
                val response = httpClient.post("$root${endpoint.path}") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (response.status.isSuccess()) response.bodyAsText() else null
            }
        }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, raw: String): T? =
        runCatchingNonCancellation { json.decodeFromString(serializer, raw) }

    /**
     * Swallows failures but rethrows [CancellationException]: a cancelled
     * composer keystroke must not be reported as a System 1 outage, and
     * swallowing it would break structured concurrency.
     */
    private inline fun <T> runCatchingNonCancellation(block: () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        null
    }

    companion object {
        /**
         * Lenient on purpose. A field added server-side must not take the fast
         * path offline on older clients; defaults in the models cover gaps.
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
