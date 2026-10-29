package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Native App Server v2 execution for runtime-owned admin operations.
 *
 * Phase 2 (runbook): [require] is fail-closed. Native timeout, missing client,
 * unsuccessful response, or open **per-command** circuit returns a typed
 * capability/error and does **not** fall through to LettaShim. [attempt]
 * remains only for legacy characterization tests that still exercise the
 * pre-cutover null-on-failure contract.
 */
internal object NativeAdmin {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    /** Fail-fast budget for native reads / idempotent probes. */
    private const val NATIVE_ATTEMPT_TIMEOUT_MS = 2_000L
    /** Budget for bulk/scan reads on disk-backed local backend stores. */
    private const val NATIVE_READ_SCAN_TIMEOUT_MS = 15_000L
    /**
     * Longer budget for mutations that may already have been accepted server-side.
     * Timing out the wait after send and inviting a retry risks duplicate creates
     * or conflicting updates. 90 s, since the on-device runtime's
     * agent_create runs memfs git on a phone (~30–40 s on a Pixel 9 Pro).
     */
    private const val NATIVE_MUTATION_TIMEOUT_MS = 90_000L
    private val COOLDOWN = 60.seconds
    private val monotonic = kotlin.time.TimeSource.Monotonic
    private val downSinceByClient = java.util.concurrent.ConcurrentLinkedQueue<Pair<
        java.lang.ref.WeakReference<AppServerClient>,
        java.util.concurrent.ConcurrentHashMap<String, kotlin.time.TimeSource.Monotonic.ValueTimeMark>,
    >>()

    private fun breakers(client: AppServerClient) = synchronized(downSinceByClient) {
        // Weak ownership avoids retaining disconnected clients; compare referents by identity,
        // since AppServerClient implementations may define value equality.
        downSinceByClient.removeAll { it.first.get() == null }
        downSinceByClient.firstOrNull { it.first.get() === client }?.second
            ?: java.util.concurrent.ConcurrentHashMap<String, kotlin.time.TimeSource.Monotonic.ValueTimeMark>()
                .also { downSinceByClient.add(java.lang.ref.WeakReference(client) to it) }
    }

    fun requestId(): String = "native-admin-${counter.incrementAndGet()}"

    private fun circuitOpen(client: AppServerClient, op: String): Boolean {
        val downSinceByOp = breakers(client)
        val down = downSinceByOp[op] ?: return false
        if (down.elapsedNow() < COOLDOWN) return true
        downSinceByOp.remove(op, down)
        return false
    }

    private fun tripBreaker(client: AppServerClient, op: String) {
        breakers(client)[op] = monotonic.markNow()
    }

    private fun clearBreaker(client: AppServerClient, op: String) {
        breakers(client).remove(op)
    }

    private fun markSelected(op: String, outcome: String, reason: String? = null) {
        AdminRouteTelemetry.selected(
            AdminRouteTelemetry.Selection(
                method = op,
                owner = "app_server_v2",
                route = "app_server_v2",
                outcome = outcome,
                reason = reason,
            ),
        )
    }

    private fun markLegacyNull(op: String, reason: String) {
        AdminRouteTelemetry.fallback(
            AdminRouteTelemetry.Fallback(
                method = op,
                fromRoute = "app_server_v2",
                toRoute = "legacy_null",
                reason = reason,
            ),
        )
    }

    /**
     * Fail-closed native execution. Never returns null for "try shim next".
     * [op] supplies both the telemetry method name and the explicit
     * [NativeAdminOperationPolicy] — no name-suffix heuristics.
     */
    @Suppress("NoAnyType") // Native commands return heterogeneous non-null model types.
    suspend fun <T : Any> require(
        client: AppServerClient?,
        op: NativeAdminOp,
        block: suspend (AppServerClient) -> T?,
    ): T {
        val method = op.method
        if (client == null) {
            markSelected(method, "unavailable", "no_client")
            adminError("capability_unavailable: $method requires App Server v2")
        }
        if (circuitOpen(client, method)) {
            markSelected(method, "unavailable", "circuit_open")
            adminError("capability_unavailable: $method App Server v2 temporarily unavailable")
        }
        return executeRequire(client, op, block)
    }

    private fun timeoutMsFor(policy: NativeAdminOperationPolicy): Long =
        when (policy) {
            NativeAdminOperationPolicy.MutationAmbiguous -> NATIVE_MUTATION_TIMEOUT_MS
            NativeAdminOperationPolicy.Read -> NATIVE_ATTEMPT_TIMEOUT_MS
            NativeAdminOperationPolicy.ReadScan -> NATIVE_READ_SCAN_TIMEOUT_MS
        }

    /** Test hook: policy for a known method, or null if unregistered. */
    internal fun policyForMethod(method: String): NativeAdminOperationPolicy? =
        NativeAdminOp.byMethod(method)?.policy

    @Suppress("NoAnyType")
    private suspend fun <T : Any> executeRequire(
        client: AppServerClient,
        op: NativeAdminOp,
        block: suspend (AppServerClient) -> T?,
    ): T {
        val method = op.method
        val timeoutMs = timeoutMsFor(op.policy)
        return try {
            completeRequire(client, method, kotlinx.coroutines.withTimeout(timeoutMs.milliseconds) { block(client) })
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            onRequireTimeout(client, method, op.policy)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            tripBreaker(client, method)
            markSelected(method, "error", e.message ?: e::class.simpleName ?: "error")
            adminError("app_server_error: $method native command failed")
        }
    }

    @Suppress("NoAnyType")
    private fun <T : Any> completeRequire(client: AppServerClient, op: String, result: T?): T {
        if (result == null) {
            markSelected(op, "error", "native_unsuccessful")
            adminError("app_server_error: $op native command unsuccessful")
        }
        clearBreaker(client, op)
        markSelected(op, "success")
        return result
    }

    /**
     * The App Server answered `success=false`: a refusal by the host, not a transport
     * failure, so the breaker is left alone. Call from inside a [require] block; the
     * thrown admin error carries the host's own [reason] (or the generic text without one).
     */
    fun refused(op: NativeAdminOp, reason: String?): Nothing {
        val detail = reason?.takeIf { it.isNotBlank() } ?: "native command unsuccessful"
        markSelected(op.method, "error", "native_unsuccessful")
        adminError("app_server_error: ${op.method} failed: $detail")
    }

    private fun onRequireTimeout(client: AppServerClient, op: String, policy: NativeAdminOperationPolicy): Nothing {
        // Mutations: report timeout but do not trip the breaker — a late success
        // on the server must not black-hole subsequent reads/writes for 60s.
        if (policy == NativeAdminOperationPolicy.Read || policy == NativeAdminOperationPolicy.ReadScan) tripBreaker(client, op)
        markSelected(op, "unavailable", "native_timeout")
        adminError("capability_unavailable: $op App Server v2 timed out")
    }

    /**
     * Legacy null-on-failure helper retained for characterization tests.
     * Production handlers must use [require].
     */
    @Suppress("NoAnyType") // Legacy helper preserves the same heterogeneous response contract.
    suspend fun <T : Any> attempt(
        client: AppServerClient?,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T? {
        if (client == null) return null
        if (circuitOpen(client, op)) {
            markLegacyNull(op, "circuit_open")
            return null
        }
        return try {
            val result = kotlinx.coroutines.withTimeout(NATIVE_ATTEMPT_TIMEOUT_MS.milliseconds) { block(client) }
            if (result != null) {
                clearBreaker(client, op)
                markSelected(op, "success")
            } else {
                markLegacyNull(op, "native_unsuccessful")
            }
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            tripBreaker(client, op)
            markLegacyNull(op, "native_timeout")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            tripBreaker(client, op)
            markLegacyNull(op, e.message ?: e::class.simpleName ?: "error")
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
                // NB: JsonObjectBuilder.put returns the PREVIOUS value for the key,
                // which is null for a fresh key — so `put(...)?.let { } ?: put(...)`
                // would always fall through to the elvis branch and overwrite the
                // numeric primitive with a string one. The App Server rejects a
                // non-number `limit` and silently falls back to a page size of 20
                // (`typeof body.limit === "number" ? body.limit : 20`), which
                // clamped every paginated admin call. Keep this as a plain if/else.
                val asLong = v.toLongOrNull()
                if (asLong != null) put(key, JsonPrimitive(asLong)) else put(key, JsonPrimitive(v))
            }
        }
    }
}
