package com.letta.mobile.data.session

interface BackendScopedCache {
    suspend fun clearForBackendSwitch()
}
