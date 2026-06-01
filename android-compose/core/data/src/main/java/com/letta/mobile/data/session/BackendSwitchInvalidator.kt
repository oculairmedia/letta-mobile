package com.letta.mobile.data.session

import com.letta.mobile.util.Telemetry
import javax.inject.Inject
import javax.inject.Singleton

interface BackendScopedCache {
    suspend fun clearForBackendSwitch()
}

/**
 * Result of attempting to clear every registered backend-scoped cache for a
 * backend switch.
 *
 * `successes` is the count of caches that cleared cleanly. `failures` carries
 * the per-cache failures (cache class name → throwable) so callers can choose
 * whether the switch should be blocked, rolled back, or proceed with a
 * surfaced warning.
 */
data class BackendSwitchClearResult(
    val successes: Int,
    val failures: List<Failure>,
) {
    val totalAttempted: Int get() = successes + failures.size
    val allSucceeded: Boolean get() = failures.isEmpty()

    data class Failure(val cacheName: String, val error: Throwable)
}

@Singleton
class BackendSwitchInvalidator @Inject constructor(
    private val caches: Set<@JvmSuppressWildcards BackendScopedCache>,
) {
    /**
     * Attempts to clear every registered backend-scoped cache for an imminent
     * backend switch. Each cache is attempted independently — a single
     * failure does not abort the rest of the invalidation. The caller gets a
     * structured [BackendSwitchClearResult] back so it can react to partial
     * failure (block the switch, roll back, alert the user).
     *
     * Per-cache failures are still telemetered here so the operational signal
     * is preserved even if the caller chooses to ignore the returned result.
     */
    suspend fun clearAll(): BackendSwitchClearResult {
        var successes = 0
        val failures = mutableListOf<BackendSwitchClearResult.Failure>()
        caches.forEach { cache ->
            val cacheName = cache.javaClass.simpleName
            runCatching {
                cache.clearForBackendSwitch()
            }.onSuccess {
                successes++
            }.onFailure { error ->
                failures += BackendSwitchClearResult.Failure(cacheName, error)
                Telemetry.error(
                    "BackendSwitch",
                    "cacheClearFailed",
                    error,
                    "cache" to cacheName,
                )
            }
        }
        return BackendSwitchClearResult(successes = successes, failures = failures)
    }
}
