package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.runtime.actions.DeviceActionCommandRunner
import com.letta.mobile.runtime.actions.InMemoryMobileActionAuditSink
import com.letta.mobile.runtime.actions.MobileActionRegistry
import com.letta.mobile.runtime.hardware.AndroidDeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.mobileactions.AndroidProviderReadTool
import com.letta.mobile.runtime.mobileactions.MobileIntentActionTool
import com.letta.mobile.runtime.sensors.AndroidDeviceSensorSnapshotProvider
import com.letta.mobile.runtime.sensors.DeviceSensorReadTool

internal fun networkBridge(context: Context): LocalAndroidNetworkBridge {
    val sensor = AndroidDeviceSensorSnapshotProvider(context)
    val hardware = AndroidDeviceHardwareControlProvider(context)
    val actions = MobileActionRegistry(emptySet(), emptySet(), InMemoryMobileActionAuditSink())
    val intents = MobileIntentActionTool(context)
    return LocalAndroidNetworkBridge(
        sensorSnapshotProvider = sensor,
        mobileActionRegistry = actions,
        mobileIntentActionTool = intents,
        hardwareControlProvider = hardware,
        deviceActionCommandRunner = DeviceActionCommandRunner(
            DeviceSensorReadTool(sensor), actions, intents, DeviceHardwareControlTool(hardware),
            AndroidProviderReadTool(context),
        ),
    )
}
