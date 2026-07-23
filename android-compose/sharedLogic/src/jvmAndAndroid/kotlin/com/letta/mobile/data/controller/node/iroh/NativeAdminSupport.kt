package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.util.Telemetry
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
     * Upper bound on a single native attempt. The native path is an OPTIMIZATION
     * over the always-available shim proxy: a localhost App-Server-v2 command that
     * succeeds does so in milliseconds. If the wrapped App Server does not answer
     * the command (e.g. letta.js hasn't implemented it), the call would otherwise
     * block for the client's full request timeout (120s) before the proxy
     * fallback runs — and the remote admin_rpc client gives up at 30s first, so
     * the read appears to "time out" even though the proxy is fast. Bounding the
     * attempt makes an unanswered native command fall back to the proxy almost
     * immediately, well inside the client's window.
     */
    private const val NATIVE_ATTEMPT_TIMEOUT_MS = 3_000L

    fun requestId(): String = "native-admin-${counter.incrementAndGet()}"

    suspend fun <T : Any> attempt(
        client: AppServerClient?,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T? {
        if (client == null) return null
        return try {
            val result = kotlinx.coroutines.withTimeout(NATIVE_ATTEMPT_TIMEOUT_MS) { block(client) }
            if (result == null) {
                Telemetry.event("IrohAdminNative", "fallback", "op" to op, "reason" to "native_unsuccessful")
            }
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A native attempt that outran its own bound is treated as a fast
            // fallback to the proxy — NOT a propagated cancellation of the whole
            // admin_rpc request. (TimeoutCancellationException is a subtype of
            // CancellationException, so it must be caught BEFORE the generic
            // CancellationException rethrow below.)
            Telemetry.event("IrohAdminNative", "fallback", "op" to op, "reason" to "native_timeout")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
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
