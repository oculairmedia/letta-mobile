package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * letta-mobile-qygvv.7: the typed subset of an `update_device_status` snapshot
 * (protocol_v2 `DeviceStatus`) that the client acts on. The frame keeps the full
 * [AppServerInboundFrame.UpdateDeviceStatus.deviceStatus] object, so unknown and
 * future fields stay tolerated; a missing or malformed field reads as null.
 */
data class AppServerDeviceStatusSnapshot(
    /** `current_working_directory` — the server's realpath of the scope's cwd. */
    val currentWorkingDirectory: String?,
    /** `current_permission_mode`; null when absent or not a mode this client knows. */
    val currentPermissionMode: AppServerPermissionMode?,
    /** `cwd_revision` — monotonic signal for cwd changes and rejected stale cwd requests. */
    val cwdRevision: Long?,
)

val AppServerInboundFrame.UpdateDeviceStatus.snapshot: AppServerDeviceStatusSnapshot
    get() = deviceStatus.toDeviceStatusSnapshot()

internal fun JsonObject.toDeviceStatusSnapshot(): AppServerDeviceStatusSnapshot =
    AppServerDeviceStatusSnapshot(
        currentWorkingDirectory = stringOrNull("current_working_directory"),
        currentPermissionMode = stringOrNull("current_permission_mode")?.let(AppServerPermissionMode::fromWireValue),
        cwdRevision = (this["cwd_revision"] as? JsonPrimitive)?.longOrNull,
    )

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
