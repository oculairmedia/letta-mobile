package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * letta-mobile-qygvv.7: shared `update_device_status` fixtures for the device-state tests
 * (engine working-directory changes and controller permission-mode changes).
 */

internal const val DEVICE_STATUS_TEST_CWD_REVISION = 7L

/** What one `update_device_status` snapshot reports; [modeWire] is the raw wire mode. */
internal data class DeviceStatusFixture(
    val cwd: String? = null,
    val modeWire: String? = null,
    val withUnknownField: Boolean = false,
) {
    fun frame(runtime: AppServerRuntimeScope) = AppServerInboundFrame.UpdateDeviceStatus(
        runtime = runtime,
        eventSeq = 1,
        emittedAt = "2026-09-24T00:00:00Z",
        idempotencyKey = "device-1",
        deviceStatus = buildJsonObject {
            cwd?.let { put("current_working_directory", it) }
            modeWire?.let { put("current_permission_mode", it) }
            put("cwd_revision", DEVICE_STATUS_TEST_CWD_REVISION)
            if (withUnknownField) put("some_future_field", "tolerated")
        },
    )

    companion object {
        fun inDirectory(cwd: String?) = DeviceStatusFixture(cwd = cwd)

        fun inMode(mode: AppServerPermissionMode) = DeviceStatusFixture(
            modeWire = AppServerProtocol.json.encodeToJsonElement(AppServerPermissionMode.serializer(), mode)
                .jsonPrimitive.content,
        )
    }
}
