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

    fun requestId(): String = "native-admin-${counter.incrementAndGet()}"

    suspend fun <T : Any> attempt(
        client: AppServerClient?,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T? {
        if (client == null) return null
        return try {
            val result = block(client)
            if (result == null) {
                Telemetry.event("IrohAdminNative", "fallback", "op" to op, "reason" to "native_unsuccessful")
            }
            result
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
