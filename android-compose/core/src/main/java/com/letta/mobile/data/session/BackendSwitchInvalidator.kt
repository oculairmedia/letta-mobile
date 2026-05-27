package com.letta.mobile.data.session

import com.letta.mobile.util.Telemetry
import javax.inject.Inject
import javax.inject.Singleton

interface BackendScopedCache {
    suspend fun clearForBackendSwitch()
}

@Singleton
class BackendSwitchInvalidator @Inject constructor(
    private val caches: Set<@JvmSuppressWildcards BackendScopedCache>,
) {
    suspend fun clearAll() {
        caches.forEach { cache ->
            runCatching {
                cache.clearForBackendSwitch()
            }.onFailure { error ->
                Telemetry.error(
                    "BackendSwitch",
                    "cacheClearFailed",
                    error,
                    "cache" to cache.javaClass.simpleName,
                )
            }
        }
    }
}
