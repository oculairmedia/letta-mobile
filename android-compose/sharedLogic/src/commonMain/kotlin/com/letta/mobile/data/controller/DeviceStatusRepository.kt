package com.letta.mobile.data.controller

import com.letta.mobile.data.transport.appserver.AppServerDeviceStatusSnapshot
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the latest [AppServerDeviceStatusSnapshot] per runtime (letta-mobile-bzvro.19).
 */
interface DeviceStatusRepository {
    fun status(runtime: AppServerRuntimeScope): StateFlow<AppServerDeviceStatusSnapshot?>
    fun status(agentId: String, conversationId: String): StateFlow<AppServerDeviceStatusSnapshot?> =
        status(AppServerRuntimeScope(agentId, conversationId))
    fun update(runtime: AppServerRuntimeScope, snapshot: AppServerDeviceStatusSnapshot)
    fun clear(runtime: AppServerRuntimeScope)
}

/** Thread-safe default in-memory implementation of [DeviceStatusRepository]. */
class DefaultDeviceStatusRepository : DeviceStatusRepository {
    private val mutex = SynchronizedObject()
    private val flows = mutableMapOf<AppServerRuntimeScope, MutableStateFlow<AppServerDeviceStatusSnapshot?>>()

    override fun status(runtime: AppServerRuntimeScope): StateFlow<AppServerDeviceStatusSnapshot?> =
        synchronized(mutex) {
            flows.getOrPut(runtime) { MutableStateFlow(null) }.asStateFlow()
        }

    override fun update(runtime: AppServerRuntimeScope, snapshot: AppServerDeviceStatusSnapshot) {
        synchronized(mutex) {
            flows.getOrPut(runtime) { MutableStateFlow(null) }.value = snapshot
        }
    }

    override fun clear(runtime: AppServerRuntimeScope) {
        synchronized(mutex) {
            flows.remove(runtime)?.value = null
        }
    }
}
