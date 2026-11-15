package com.letta.mobile.data.transport.api

import com.letta.mobile.data.toolsets.ToolsetOption
import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.toolsets.ToolsetState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.toDeviceStatusSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection-owned projection of host device status into the toolset picker's portable state. */
class ToolsetStatusStore {
    private val lock = SynchronizedObject()
    private var epoch = 0L
    private var expectedGeneration: Long? = null
    private val hosts = MutableStateFlow<Map<ToolsetRuntime, ToolsetState>>(emptyMap())

    /** Latest preferences and choices, keyed by their exact agent and conversation. */
    val states: StateFlow<Map<ToolsetRuntime, ToolsetState>> = hosts.asStateFlow()

    /** Admits only this connection's device-status frames; other runtimes keep their own entries. */
    fun observe(received: AppServerReceivedFrame, generation: Long? = null) {
        val observedEpoch = beginObservation(received, generation) ?: return
        val payload = payload(received.frame) ?: return
        val snapshot = payload.second.toDeviceStatusSnapshot()
        val runtime = ToolsetRuntime(payload.first.agentId, payload.first.conversationId)
        val state = ToolsetState(
            available = snapshot.availableToolsets.map { ToolsetOption(it.id, it.displayName ?: it.label ?: it.id) },
            preference = snapshot.toolsetPreference,
        )
        synchronized(lock) {
            if (epoch == observedEpoch) hosts.value = hosts.value + (runtime to state)
        }
    }

    private fun beginObservation(received: AppServerReceivedFrame, generation: Long?): Long? = synchronized(lock) {
        if (generation == null) return@synchronized epoch
        if (received.connectionGeneration?.let { it != generation } == true) return@synchronized null
        if (expectedGeneration?.let { generation < it } == true) return@synchronized null
        if (expectedGeneration != generation) {
            epoch++
            hosts.value = emptyMap()
            expectedGeneration = generation
        }
        epoch
    }

    private fun payload(frame: AppServerInboundFrame): Pair<AppServerRuntimeScope, JsonObject>? = when (frame) {
        is AppServerInboundFrame.UpdateDeviceStatus -> frame.runtime to frame.deviceStatus
        is AppServerInboundFrame.Unknown -> notification(frame.raw)
        else -> null
    }

    private fun notification(raw: JsonObject): Pair<AppServerRuntimeScope, JsonObject>? {
        if (raw["type"] != JsonPrimitive("toolset_status")) return null
        val runtime = raw["runtime"] as? JsonObject ?: return null
        val status = raw["device_status"] as? JsonObject ?: return null
        val scope = runCatching {
            AppServerProtocol.json.decodeFromJsonElement(AppServerRuntimeScope.serializer(), runtime)
        }.getOrNull()
        return scope?.let { it to status }
    }

    /** Drops the previous connection's choices before a successor connection supplies fresh status. */
    fun clear(generation: Long? = null) = synchronized(lock) {
        expectedGeneration = generation
        epoch++
        hosts.value = emptyMap()
    }
}
