package com.letta.mobile.data.repository

/**
 * Platform-neutral result of clearing backend-scoped caches on config switch.
 * Android maps [com.letta.mobile.data.session.BackendSwitchClearResult] into this shape.
 */
data class SettingsBackendCacheClearResult(
    val successes: Int,
    val failedCacheNames: List<String>,
) {
    val allSucceeded: Boolean get() = failedCacheNames.isEmpty()
}

fun interface SettingsBackendCacheClearer {
    suspend fun clearAll(): SettingsBackendCacheClearResult
}
