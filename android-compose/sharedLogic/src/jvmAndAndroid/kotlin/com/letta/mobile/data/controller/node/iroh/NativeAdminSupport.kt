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
 * unsuccessful response, or open **per-command** circuit returns a typed
 * capability/error and does **not** fall through to LettaShim. [attempt]
 * remains only for legacy characterization tests that still exercise the
 * pre-cutover null-on-failure contract.
 */
internal object NativeAdmin {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    /** Fail-fast budget for native reads / idempotent probes. */
    private const val NATIVE_ATTEMPT_TIMEOUT_MS = 2_000L
    /**
     * Longer budget for mutations that may already have been accepted server-side.
     * Timing out the wait after send and inviting a retry risks duplicate creates
     * or conflicting updates.
     */
    private const val NATIVE_MUTATION_TIMEOUT_MS = 30_000L
    private val COOLDOWN = 60.seconds
    private val monotonic = kotlin.time.TimeSource.Monotonic
    private val downSinceByOp =
        java.util.concurrent.ConcurrentHashMap<String, kotlin.time.TimeSource.Monotonic.ValueTimeMark>()

    fun requestId(): String = "native-admin-${counter.incrementAndGet()}"

    private fun circuitOpen(op: String): Boolean {
        val down = downSinceByOp[op] ?: return false
        if (down.elapsedNow() < COOLDOWN) return true
        downSinceByOp.remove(op, down)
        return false
    }

    private fun tripBreaker(op: String) {
        downSinceByOp[op] = monotonic.markNow()
    }

    private fun clearBreaker(op: String) {
        downSinceByOp.remove(op)
    }

    /** Test hook: clear all per-command breakers so cases don't leak state. */
    internal fun resetCircuitForTest() {
        downSinceByOp.clear()
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
     */
    suspend fun <T : Any> require(
        client: AppServerClient?,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T {
        if (client == null) {
            markSelected(op, "unavailable", "no_client")
            adminError("capability_unavailable: $op requires App Server v2")
        }
        if (circuitOpen(op)) {
            markSelected(op, "unavailable", "circuit_open")
            adminError("capability_unavailable: $op App Server v2 temporarily unavailable")
        }
        return executeRequire(client, op, block)
    }

    private fun timeoutMsFor(op: String): Long =
        if (isMutationOp(op)) NATIVE_MUTATION_TIMEOUT_MS else NATIVE_ATTEMPT_TIMEOUT_MS

    /** True for ops that may already have mutated durable App Server state when a wait times out. */
    internal fun isMutationOp(op: String): Boolean {
        val name = op.lowercase()
        if (name in MUTATION_EXACT_OPS) return true
        return MUTATION_SUFFIXES.any { name.endsWith(it) }
    }

    private val MUTATION_EXACT_OPS = setOf(
        "skill_enable",
        "skill_disable",
        "skill.install",
        "skill.uninstall",
        "approval.submit",
    )

    private val MUTATION_SUFFIXES = listOf(
        ".create",
        ".update",
        ".delete",
        ".delete_all",
        ".archive",
    )

    private suspend fun <T : Any> executeRequire(
        client: AppServerClient,
        op: String,
        block: suspend (AppServerClient) -> T?,
    ): T {
        val timeoutMs = timeoutMsFor(op)
        return try {
            completeRequire(op, kotlinx.coroutines.withTimeout(timeoutMs) { block(client) })
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            onRequireTimeout(op)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            tripBreaker(op)
            markSelected(op, "error", e.message ?: e::class.simpleName ?: "error")
            adminError("app_server_error: $op native command failed")
        }
    }

    private fun <T : Any> completeRequire(op: String, result: T?): T {
        if (result == null) {
            markSelected(op, "error", "native_unsuccessful")
            adminError("app_server_error: $op native command unsuccessful")
        }
        clearBreaker(op)
        markSelected(op, "success")
        return result
    }

    private fun onRequireTimeout(op: String): Nothing {
        // Mutations: report timeout but do not trip the breaker — a late success
        // on the server must not black-hole subsequent reads/writes for 60s.
        if (!isMutationOp(op)) tripBreaker(op)
        markSelected(op, "unavailable", "native_timeout")
        adminError("capability_unavailable: $op App Server v2 timed out")
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
        if (circuitOpen(op)) {
            markLegacyNull(op, "circuit_open")
            return null
        }
        return try {
            val result = kotlinx.coroutines.withTimeout(NATIVE_ATTEMPT_TIMEOUT_MS) { block(client) }
            if (result != null) {
                clearBreaker(op)
                markSelected(op, "success")
            } else {
                markLegacyNull(op, "native_unsuccessful")
            }
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            tripBreaker(op)
            markLegacyNull(op, "native_timeout")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            tripBreaker(op)
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
                v.toLongOrNull()?.let { put(key, JsonPrimitive(it)) } ?: put(key, JsonPrimitive(v))
            }
        }
    }
}
