package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Native App Server v2 execution for runtime-owned admin operations.
 *
 * Phase 2 (runbook): [require] is fail-closed. Native timeout, missing client,
 * unsuccessful response, or open circuit returns a typed capability/error and
 * does **not** fall through to LettaShim. [attempt] remains only for legacy
 * characterization tests that still exercise the pre-cutover null-on-failure
 * contract.
 */
internal object NativeAdmin {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Upper bound on a single native attempt. A native App-Server-v2 command that
     * the wrapped server actually implements answers in milliseconds on localhost;
     * this bound only matters when the command goes UNANSWERED.
     */
    private const val NATIVE_ATTEMPT_TIMEOUT_MS = 2_000L

    /**
     * Per-process cooldown after native timeout/error. Phase 2 uses this to fail
     * closed quickly (`capability_unavailable`) instead of probing a dead App
     * Server on every admin_rpc. A native SUCCESS clears the breaker immediately.
     */
    private val COOLDOWN = 60.seconds
    private val monotonic = kotlin.time.TimeSource.Monotonic

    @Volatile
    private var nativeDownSince: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null

    fun requestId(): String = "native-admin-${counter.incrementAndGet()}"

    private fun circuitOpen(): Boolean {
        val down = nativeDownSince ?: return false
        return if (down.elapsedNow() < COOLDOWN) true else { nativeDownSince = null; false }
    }

    private fun tripBreaker() {
        nativeDownSince = monotonic.markNow()
    }

    /** Test hook: clear the circuit breaker so cases don't leak state across each other. */
    internal fun resetCircuitForTest() {
        nativeDownSince = null
    }

    /**
     * Fail-closed native execution. Never returns null for "try shim next".
     */
    suspend fun <T : Any> require(
        client: AppServerClient?,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T {
        if (client == null) {
            AdminRouteTelemetry.selected(
                method = op,
                owner = "app_server_v2",
                route = "app_server_v2",
                outcome = "unavailable",
                reason = "no_client",
            )
            adminError("capability_unavailable: $op requires App Server v2")
        }
        if (circuitOpen()) {
            AdminRouteTelemetry.selected(
                method = op,
                owner = "app_server_v2",
                route = "app_server_v2",
                outcome = "unavailable",
                reason = "circuit_open",
            )
            adminError("capability_unavailable: $op App Server v2 temporarily unavailable")
        }
        return try {
            val result = kotlinx.coroutines.withTimeout(NATIVE_ATTEMPT_TIMEOUT_MS) { block(client) }
            if (result == null) {
                AdminRouteTelemetry.selected(
                    method = op,
                    owner = "app_server_v2",
                    route = "app_server_v2",
                    outcome = "error",
                    reason = "native_unsuccessful",
                )
                adminError("app_server_error: $op native command unsuccessful")
            }
            nativeDownSince = null
            AdminRouteTelemetry.selected(
                method = op,
                owner = "app_server_v2",
                route = "app_server_v2",
                outcome = "success",
            )
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            tripBreaker()
            AdminRouteTelemetry.selected(
                method = op,
                owner = "app_server_v2",
                route = "app_server_v2",
                outcome = "unavailable",
                reason = "native_timeout",
            )
            adminError("capability_unavailable: $op App Server v2 timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // adminError / param validation — do not trip the breaker.
            throw e
        } catch (e: Exception) {
            tripBreaker()
            AdminRouteTelemetry.selected(
                method = op,
                owner = "app_server_v2",
                route = "app_server_v2",
                outcome = "error",
                reason = e.message ?: e::class.simpleName ?: "error",
            )
            adminError("app_server_error: $op ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Legacy null-on-failure helper retained for characterization tests.
     * Production handlers must use [require].
     */
    suspend fun <T : Any> attempt(
        client: AppServerClient?,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T? {
        if (client == null) return null
        if (circuitOpen()) {
            AdminRouteTelemetry.fallback(
                method = op,
                fromRoute = "app_server_v2",
                toRoute = "legacy_null",
                reason = "circuit_open",
            )
            return null
        }
        return try {
            val result = kotlinx.coroutines.withTimeout(NATIVE_ATTEMPT_TIMEOUT_MS) { block(client) }
            if (result != null) {
                nativeDownSince = null
                AdminRouteTelemetry.selected(
                    method = op,
                    owner = "app_server_v2",
                    route = "app_server_v2",
                    outcome = "success",
                )
            } else {
                AdminRouteTelemetry.fallback(
                    method = op,
                    fromRoute = "app_server_v2",
                    toRoute = "legacy_null",
                    reason = "native_unsuccessful",
                )
            }
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            tripBreaker()
            AdminRouteTelemetry.fallback(
                method = op,
                fromRoute = "app_server_v2",
                toRoute = "legacy_null",
                reason = "native_timeout",
            )
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            tripBreaker()
            AdminRouteTelemetry.fallback(
                method = op,
                fromRoute = "app_server_v2",
                toRoute = "legacy_null",
                reason = e.message ?: e::class.simpleName ?: "error",
            )
            null
        }
    }

    /** Builds a native query object from pagination-style string params. */
    fun queryOf(vararg pairs: Pair<String, String?>): JsonObject? {
        val present = pairs.filter { it.second != null }
        if (present.isEmpty()) return null
        return buildJsonObject {
            present.forEach { (key, value) ->
                val v = value!!
                v.toLongOrNull()?.let { put(key, JsonPrimitive(it)) } ?: put(key, JsonPrimitive(v))
            }
        }
    }
}
