package com.letta.mobile.runtime.sensors

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSensorGatedCapabilitiesTest {
    @Test
    fun `default posture disables gated capabilities without permission prompts`() {
        val packageManager = mockk<PackageManager> {
            every { hasSystemFeature(PackageManager.FEATURE_SENSOR_HEART_RATE) } returns false
        }
        val context = mockk<Context> {
            every { this@mockk.packageManager } returns packageManager
        }

        val caps = AndroidDeviceSensorGatedCapabilityProvider(context).listCapabilities()

        assertEquals(5, caps.size)
        assertEquals(DeviceSensorGatedStatus.Disabled, caps.single { it.id == DeviceSensorGatedCapabilityIds.LOCATION }.status)
        assertEquals(DeviceSensorGatedStatus.Disabled, caps.single { it.id == DeviceSensorGatedCapabilityIds.WIFI_SSID }.status)
        assertEquals(DeviceSensorGatedStatus.Disabled, caps.single { it.id == DeviceSensorGatedCapabilityIds.ACTIVITY_RECOGNITION }.status)
        assertEquals(DeviceSensorGatedStatus.Disabled, caps.single { it.id == DeviceSensorGatedCapabilityIds.NOTIFICATION_NOW_PLAYING }.status)
        assertEquals(DeviceSensorGatedStatus.Unavailable, caps.single { it.id == DeviceSensorGatedCapabilityIds.BODY_SENSORS }.status)
        assertTrue(caps.all { !it.defaultEnabled })
    }

    @Test
    fun `read_sensors summary includes gated capability statuses`() {
        val tool = DeviceSensorReadTool(
            provider = object : DeviceSensorSnapshotProvider {
                override fun snapshot(nowMillis: Long): DeviceSensorSnapshot = DeviceSensorSnapshot(
                    capturedAtMillis = nowMillis,
                    sensors = emptyList(),
                    gatedCapabilities = listOf(
                        DeviceSensorGatedCapability(
                            id = DeviceSensorGatedCapabilityIds.LOCATION,
                            label = "Precise location",
                            status = DeviceSensorGatedStatus.Disabled,
                            defaultEnabled = false,
                            runtimePermissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
                            reason = "disabled until explicit opt-in",
                        )
                    ),
                )
            }
        )

        val response = tool.handle(kotlinx.serialization.json.buildJsonObject { put("mode", "summary") }, 1L)

        assertEquals(1, response.gatedCapabilities.size)
        assertEquals(DeviceSensorGatedStatus.Disabled, response.gatedCapabilities.single().status)
        assertTrue(response.summary.contains("gated=0/1"))
    }
}
