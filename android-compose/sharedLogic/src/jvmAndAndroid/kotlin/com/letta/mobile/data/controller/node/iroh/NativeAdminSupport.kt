package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.util.Telemetry
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Native-first execution for runtime-native admin operations (lgns8.7).
 *
 * Operations classified `app_server_v2` in the lgns8.13 ownership matrix try
 * the documented native command over the App Server client first and fall
 * back to the shim HTTP proxy (`shim_until_cutover`) when no client is wired,
 * the connection is down, or the native call fails. Fallbacks are audited so
 * cutover readiness (lgns8.10/.11) can measure native coverage.
 */
internal object NativeAdmin {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Upper bound on a single native attempt. A native App-Server-v2 command that
     * the wrapped server actually implements answers in milliseconds on localhost;
     * this bound only matters when the command goes UNANSWERED (e.g. the deployed
     * letta.js doesn't implement admin queries yet — lgns8.10/.11). Without it the
     * attempt would ride the client's full request timeout (120s) before the proxy
     * fallback, and the remote admin_rpc client gives up at 30s first, so the read
     * surfaces as "admin_rpc timed out" even though the proxy fallback is fast.
     */
    private const val NATIVE_ATTEMPT_TIMEOUT_MS = 2_000L

    /**
     * Circuit breaker. When the native path proves unavailable (timeout or error —
     * the wrapped App Server doesn't implement these admin commands), skip it and
     * go straight to the proxy for a cooldown instead of paying the probe on EVERY
     * op. A page-heavy read (agent.list across ~20 offsets, conversation/message
     * lists, …) otherwise multiplies the probe into many seconds of dead wait.
     * Re-probed once per cooldown so native lights up automatically the moment
     * letta.js gains the commands. Global (all ops) because the gap is per-server,
     * not per-op. A native SUCCESS clears the breaker immediately.
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

    suspend fun <T : Any> attempt(
        client: AppServerClient?,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T? {
        if (client == null) return null
        // Native known-unavailable: don't probe, go straight to the proxy.
        if (circuitOpen()) return null
        return try {
            val result = kotlinx.coroutines.withTimeout(NATIVE_ATTEMPT_TIMEOUT_MS) { block(client) }
            if (result != null) {
                nativeDownSince = null // native answered — prefer it again
            } else {
                Telemetry.event("IrohAdminNative", "fallback", "op" to op, "reason" to "native_unsuccessful")
            }
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A native attempt that outran its own bound is a fast fallback to the
            // proxy — NOT a propagated cancellation of the whole admin_rpc request.
            // (TimeoutCancellationException is a CancellationException subtype, so
            // it must be caught BEFORE the generic rethrow below.) Trip the breaker
            // so sibling/subsequent ops skip the dead native path.
            tripBreaker()
            Telemetry.event("IrohAdminNative", "fallback", "op" to op, "reason" to "native_timeout")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            tripBreaker()
            Telemetry.event(
                "IrohAdminNative", "fallback",
                "op" to op,
                "reason" to (e.message ?: e::class.simpleName ?: "error"),
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
